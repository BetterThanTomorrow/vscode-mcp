(ns vscode-mcp.stdio.wrapper
  "The stdio wrapper's shell. It turns what happens on stdin, on the socket and on its
   timers into actions for `wrapper-axs/handle-action`, and performs the effects that
   come back. The decisions are in `vscode-mcp.stdio.wrapper-axs`."
  (:require
   ["fs" :as fs]
   ["net" :as net]
   ["process" :as process]
   ["path" :as path]
   [clojure.string :as string]
   [vscode-mcp.stdio.window-lookup :as window-lookup]
   [vscode-mcp.stdio.wrapper-axs :as wrapper-axs]))

(def ^:private log-levels {:error 0
                           :warn 1
                           :info 2
                           :debug 3})

(def ^:private min-log-level
  (let [arg-level (some #(when (.startsWith % "--min-log-level=")
                           (subs % (count "--min-log-level=")))
                        (js->clj (.-argv process)))
        level-kw (when arg-level (keyword arg-level))]
    (get log-levels level-kw :debug)))

(defn- log-stderr
  ([args] (log-stderr :debug args))
  ([level & args]
   (when (<= (get log-levels level 0) (get log-levels min-log-level))
     (.write (.-stderr process) (str "[Wrapper] " (string/join " " args) "\n")))))

;; Redirect console output to stderr, defaulting to debug level
(set! js/console.log (partial log-stderr :debug))
(set! js/console.error (partial log-stderr :error))

(def ^:private original-stdout (.-stdout process))

(defn- read-port-from-file [port-file-path]
  (js/Promise.
   (fn [resolve _reject]
     (.readFile fs port-file-path #js {:encoding "utf8"}
                (fn [err data]
                  (if err
                    (do (log-stderr :error "Port file read error:" err)
                        (resolve nil))
                    (let [port-num (js/parseInt data 10)]
                      (if (js/isNaN port-num)
                        (do (log-stderr :error "Invalid port number in file:" data)
                            (resolve nil))
                        (resolve port-num)))))))))

;; The state and the live objects

(defonce ^:private !state (atom nil))

(defonce ^{:private true
           :doc "The socket, while it connects and once it is connected, and the timer of the next connect attempt."}
  !live-objects
  (atom {}))

(defn- clear-timer! []
  (when-let [timer (:timer @!live-objects)]
    (js/clearTimeout timer)
    (swap! !live-objects dissoc :timer)))

(defn- destroy-socket! []
  (when-let [^js socket (:socket @!live-objects)]
    (.destroy socket)
    (swap! !live-objects dissoc :socket)))

;; The effects. Each takes `dispatch!` and the arguments of its effect vector.

(defn- log
  "Writes `args` to stderr at `level`."
  [_dispatch! level & args]
  (apply log-stderr level args))

(defn- attempt-failed
  "The action for a connect attempt that failed with `error`."
  [error]
  [:connect/ax.failed {:connect/now-ms (js/Date.now)
                       :connect/reason (str error)}])

(defn- read-entries
  "Reads the window registry's live entries for `search`, and dispatches them."
  [dispatch! search script-name]
  (-> (js/Promise.resolve nil)
      (.then (fn [_]
               (window-lookup/read-caller-entries search script-name)))
      (.then (fn [entries]
               (dispatch! [:connect/ax.entries-read {:connect/entries entries
                                                     :connect/now-ms (js/Date.now)}]))
             (fn [error]
               (dispatch! (attempt-failed error))))))

(defn- read-port-file
  "Reads the port in `port-file`, and dispatches it with `host`. The port is nil when
   the file has none."
  [dispatch! port-file host]
  (.then (read-port-from-file port-file)
         (fn [port]
           (dispatch! [:connect/ax.port-read {:connect/port port
                                              :connect/host host
                                              :connect/now-ms (js/Date.now)}]))))

(defn- connect
  "Connects to `port` on `host`, and dispatches that it connected, or that the attempt failed."
  [dispatch! port host]
  (try
    (let [^js socket (net/connect #js {:port port :host host})
          on-error (fn [^js error]
                     (log-stderr :debug "Socket error during connect:" (.-message error))
                     (destroy-socket!)
                     (dispatch! (attempt-failed (.-message error))))]
      (swap! !live-objects assoc :socket socket)
      (.once socket "error" on-error)
      (.once socket "connect"
             (fn []
               (.off socket "error" on-error)
               (dispatch! [:connect/ax.connected {:connect/port port
                                                  :connect/host host}]))))
    (catch :default error
      (dispatch! (attempt-failed error)))))

(defn- retry-after
  "Dispatches the next connect attempt after `ms` milliseconds."
  [dispatch! ms]
  (clear-timer!)
  (swap! !live-objects assoc :timer
         (js/setTimeout (fn []
                          (swap! !live-objects dissoc :timer)
                          (dispatch! [:connect/ax.attempt]))
                        ms)))

(defn- cancel
  "Stops connecting: the next attempt isn't made, and a socket that is connecting, or
   has just connected, is closed."
  [_dispatch!]
  (clear-timer!)
  (destroy-socket!))

(defn- relay
  "Dispatches what happens on the connected socket from here on."
  [dispatch!]
  (let [^js socket (:socket @!live-objects)]
    (.setEncoding socket "utf8")
    (.on socket "data" (fn [chunk]
                         (log-stderr :debug "Received from socket:" chunk)
                         (dispatch! [:socket/ax.data chunk])))
    (.on socket "end" (fn []
                        (dispatch! [:socket/ax.ended])))
    (.on socket "error" (fn [^js error]
                          (dispatch! [:socket/ax.error (.-message error)])))
    (.on socket "close" (fn [had-error?]
                          (dispatch! [:socket/ax.closed had-error?])))))

(defn- write-to-socket
  "Writes `line` to the connected socket."
  [_dispatch! line]
  (when-let [^js socket (:socket @!live-objects)]
    (.write socket (str line "\n"))))

(defn- write-to-stdout
  "Writes `line` to stdout."
  [_dispatch! line]
  (.write original-stdout (str line "\n")))

(defn- exit
  "Exits the process with `code`."
  [_dispatch! code]
  (.exit process code))

(def ^:private effects
  {:log/fx.write #'log
   :connect/fx.read-entries #'read-entries
   :connect/fx.read-port-file #'read-port-file
   :connect/fx.connect #'connect
   :connect/fx.retry-after #'retry-after
   :connect/fx.cancel #'cancel
   :socket/fx.relay #'relay
   :socket/fx.write #'write-to-socket
   :stdout/fx.write #'write-to-stdout
   :process/fx.exit #'exit})

(defn- dispatch!
  "Handles `action`, and performs the effects it gives, in order."
  [action]
  (let [{:uf/keys [db fxs]} (wrapper-axs/handle-action @!state action)]
    (reset! !state db)
    (doseq [[effect-name & args] fxs]
      (if-let [effect (get effects effect-name)]
        (apply effect dispatch! args)
        (log-stderr :error "Unknown effect:" (pr-str effect-name))))))

(defn- dispatch-stdin!
  "Dispatches what happens on `stdin`."
  [^js stdin]
  (.setEncoding stdin "utf8")
  (.on stdin "data" (fn [chunk]
                      (log-stderr :debug "Raw stdin chunk received:" chunk)
                      (dispatch! [:stdin/ax.data chunk])))
  (.on stdin "error" (fn [error]
                       (log-stderr :error "stdin error:" error)))
  (.on stdin "end" (fn []
                     (dispatch! [:stdin/ax.ended])))
  (.on stdin "close" (fn []
                       (log-stderr :info "stdin closed.")
                       (dispatch! [:stdin/ax.ended]))))

(defn ^:export main [& args]

  (log-stderr :info "Running in node version: " (.-version process))

  (let [script-name (path/basename (nth (.-argv process) 1 "mcp-server.js"))
        [port-or-port-file host] (remove #(string/starts-with? % "--") args)
        search (when-not port-or-port-file
                 (window-lookup/current-caller-search))
        entries (when search
                  (window-lookup/read-caller-entries search script-name))]
    (dispatch-stdin! (.-stdin process))
    (dispatch! [:wrapper/ax.start {:wrapper/script-name script-name
                                   :connect/port-or-port-file port-or-port-file
                                   :connect/host host
                                   :connect/search search
                                   :connect/entries entries
                                   :connect/now-ms (js/Date.now)}])))
