(ns vscode-mcp.stdio.wrapper
  (:require
   ["fs" :as fs]
   ["net" :as net]
   ["process" :as process]
   ["path" :as path]
   [clojure.string :as string]
   [vscode-mcp.stdio-config :as stdio-config]
   [vscode-mcp.stdio.connect-policy :as connect-policy]
   [vscode-mcp.stdio.window-lookup :as window-lookup]))

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

(defn- process-newline-buffer! [buffer on-line]
  (loop []
    (let [buffer-val @buffer
          newline-pos (.indexOf buffer-val "\n")]
      (if (>= newline-pos 0)
        (let [message-part (subs buffer-val 0 newline-pos)
              _ (vreset! buffer (subs buffer-val (inc newline-pos)))
              message (string/trim message-part)]
          (when-not (string/blank? message)
            (on-line message))
          (recur))
        nil))))

(defn- flush-newline-buffer!
  "On graceful stream end, emit any remaining buffered content as a final
   message. A well-behaved peer terminates messages with a newline, but the
   last message may arrive without one; this avoids silently dropping it."
  [buffer on-line]
  (let [remaining (string/trim @buffer)]
    (vreset! buffer "")
    (when-not (string/blank? remaining)
      (on-line remaining))))

(defn- forward-line-to-socket! [^js socket message]
  (log-stderr :info "Complete message segment from stdin, sending to socket:" message)
  (.write socket (str message "\n")))

(def ^:private port-file-give-up-message
  "MCP server not reachable within 60s — port file missing or server not accepting connections")

(defn- request-ids
  "Ids of the JSON-RPC requests among `lines`."
  [lines]
  (keep (fn [line]
          (try
            (let [id (.-id ^js (js/JSON.parse line))]
              (when (some? id) id))
            (catch :default _ nil)))
        lines))

(defn- write-give-up-error!
  "Emit -32001 after connect budget exhausted: one response per queued request, or one
   without an id when nothing is queued. Caller must exit 1."
  [message queued-lines]
  (log-stderr :error message)
  (doseq [id (or (seq (request-ids queued-lines)) [nil])]
    (.write original-stdout
            (str (js/JSON.stringify
                  (cond-> #js {:jsonrpc "2.0"
                               :error #js {:code -32001
                                           :message message}}
                    (some? id) (doto (unchecked-set "id" id))))
                 "\n"))))

(defn- port-or-port-file-source
  "Port source for a port or a port file path given on the command line."
  [port-or-port-file]
  (fn []
    (if-let [parsed-port (parse-long port-or-port-file)]
      (js/Promise.resolve parsed-port)
      (read-port-from-file port-or-port-file))))

(defn- caller-window-source
  "Port source that finds the caller's window and reads the port file of the wrapper's
   server there. `:give-up-message` says what was looked for."
  [script-name]
  (let [search (window-lookup/current-caller-search)]
    (log-stderr :info "Looking for the caller's window:" (window-lookup/caller-search-text search))
    {:port-source (fn []
                    (let [entries (window-lookup/read-live-entries)
                          port-file (get-in (window-lookup/target entries search script-name)
                                            [:target/entry :mcp :portFilePath])]
                      (if port-file
                        (read-port-from-file port-file)
                        (js/Promise.resolve nil))))
     :settled-without-window? (fn []
                                (window-lookup/settled-without-window?
                                 (window-lookup/read-live-entries) search))
     :give-up-message (fn []
                        (window-lookup/failure-message
                         (window-lookup/read-live-entries) search script-name))}))

(defn- handle-stdin
  [^js stdin ^js socket & [{:keys [initial-buffer]}]]
  (let [stdin-buffer (volatile! (or initial-buffer ""))
        forward! (fn [message] (forward-line-to-socket! socket message))]
    (.setEncoding stdin "utf8")

    (.on stdin "data"
         (fn [chunk]
           (log-stderr :debug "Raw stdin chunk received:" chunk)
           (vswap! stdin-buffer str chunk)
           (process-newline-buffer! stdin-buffer forward!)))

    (.on stdin "error" (fn [err] (log-stderr :error "stdin error:" err)))

    (.on stdin "end" (fn [] (flush-newline-buffer! stdin-buffer forward!)))

    (.on stdin "close" (fn [] (log-stderr :info "stdin closed.")))))

(defn- json-message? [s]
  (and (string? s)
       (not (string/blank? s))
       (or (.startsWith s "{")
           (.startsWith s "["))))

(defn- handle-socket [^js socket]
  (.setEncoding socket "utf8")

  (let [socket-buffer (volatile! "")
        forward! (fn [message]
                   (if (json-message? message)
                     (do
                       (log-stderr :info "Sending to stdout:" message)
                       (.write original-stdout (str message "\n")))
                     (log-stderr :warn "Filtered potential non-JSON output from socket:" message)))]
    ;; Forward socket server responses to stdout
    (.on socket "data"
         (fn [data]
           (log-stderr :debug "Received from socket:" data)
           (vswap! socket-buffer str data)
           (process-newline-buffer! socket-buffer forward!)))

    ;; Flush a trailing newline-less message on graceful end
    (.on socket "end" (fn [] (flush-newline-buffer! socket-buffer forward!)))

    ;; Handle socket errors
    (.on socket "error"
         (fn [err]
           (log-stderr :error "Socket error:" err)
           (.write original-stdout
                   (str (js/JSON.stringify
                         #js {:jsonrpc "2.0"
                              :error #js {:code -32000
                                          :message (str "Server connection error: "
                                                        (.-message err))}})
                        "\n"))
           (.exit process 1)))

    ;; Handle socket close
    (.on socket "close"
         (fn [had-error?]
           (log-stderr :info (if had-error?
                               "Socket closed due to transmission error."
                               "Socket connection closed cleanly."))
           (.exit process (if had-error? 1 0))))))

(defn- attach-wait-phase-stdin!
  [^js stdin {:keys [line-queue buffer ended? on-wait-abort!]}]
  (.setEncoding stdin "utf8")
  (let [on-data (fn [chunk]
                  (log-stderr :debug "Raw stdin chunk received (wait phase):" chunk)
                  (vswap! buffer str chunk)
                  (process-newline-buffer! buffer
                                           (fn [line] (vswap! line-queue conj line))))
        on-end (fn []
                 (flush-newline-buffer! buffer
                                        (fn [line] (vswap! line-queue conj line)))
                 (vreset! ended? true)
                 (on-wait-abort!))
        on-close (fn []
                   (log-stderr :info "stdin closed during wait phase.")
                   (vreset! ended? true)
                   (on-wait-abort!))
        on-error (fn [err] (log-stderr :error "stdin error:" err))]
    (.on stdin "data" on-data)
    (.on stdin "error" on-error)
    (.on stdin "end" on-end)
    (.on stdin "close" on-close)
    {:on-data on-data :on-error on-error :on-end on-end :on-close on-close}))

(defn- detach-wait-phase-stdin!
  [^js stdin handlers]
  (.off stdin "data" (:on-data handlers))
  (.off stdin "error" (:on-error handlers))
  (.off stdin "end" (:on-end handlers))
  (.off stdin "close" (:on-close handlers)))

(defn- connect-with-retry!+
  [host options {:keys [port-source give-up-message stdin-ended? cleanup-connect! queued-lines]}]
  (js/Promise.
   (fn [resolve _reject]
     (let [start-ms (js/Date.now)
           pending-timer (volatile! nil)
           in-flight-socket (volatile! nil)
           gave-up? (volatile! false)
           clear-pending-timer! (fn []
                                  (when-let [t @pending-timer]
                                    (js/clearTimeout t)
                                    (vreset! pending-timer nil)))
           destroy-in-flight-socket! (fn []
                                       (when-let [^js s @in-flight-socket]
                                         (.destroy s)
                                         (vreset! in-flight-socket nil)))
           give-up! (fn []
                      (when-not @gave-up?
                        (vreset! gave-up? true)
                        (clear-pending-timer!)
                        (destroy-in-flight-socket!)
                        (write-give-up-error! (give-up-message) @queued-lines)
                        (.exit process 1)))]
       (letfn [(handle-retryable-failure! [reason]
                 (when @stdin-ended?
                   (clear-pending-timer!)
                   (destroy-in-flight-socket!)
                   (.exit process 0)
                   nil)
                 (when-not (or @gave-up? @stdin-ended?)
                   (let [decision (connect-policy/attempt-decision
                                   {:connect/start-ms start-ms
                                    :connect/now-ms (js/Date.now)}
                                   options)]
                     (log-stderr :debug "Connect attempt failed:" reason
                                 "elapsed-ms" (:connect/elapsed-ms decision)
                                 "action" (:connect/action decision))
                     (if (= (:connect/action decision) :give-up)
                       (give-up!)
                       (let [timer (js/setTimeout
                                    attempt-connect!
                                    (:connect/delay-ms decision))]
                         (vreset! pending-timer timer))))))
               (attempt-connect! []
                 (when (and (not @gave-up?) (not @stdin-ended?))
                   (log-stderr :debug "Attempting MCP server connection…")
                   (-> (port-source)
                       (.then (fn [port]
                                (when-not @stdin-ended?
                                  (if port
                                    (let [connect-host (stdio-config/normalize-host host)
                                          socket (net/connect #js {:port port :host connect-host})]
                                      (vreset! in-flight-socket socket)
                                      (.once socket "error"
                                             (fn [err]
                                               (log-stderr :debug "Socket error during connect:" (.-message err))
                                               (destroy-in-flight-socket!)
                                               (handle-retryable-failure! (.-message err))))
                                      (.once socket "connect"
                                             (fn []
                                               (if (or @gave-up? @stdin-ended?)
                                                 (.destroy socket)
                                                 (do
                                                   (vreset! in-flight-socket nil)
                                                   (clear-pending-timer!)
                                                   (resolve {:socket socket
                                                             :port port
                                                             :connect-host connect-host}))))))
                                    (handle-retryable-failure! "port unavailable")))))
                       (.catch (fn [err]
                                 (handle-retryable-failure! (str err)))))))]
         (vreset! cleanup-connect!
                  (fn []
                    (vreset! gave-up? true)
                    (clear-pending-timer!)
                    (destroy-in-flight-socket!)))
         (log-stderr :info "waiting for MCP server…")
         (attempt-connect!))))))

(def ^:private settled-timeout-ms
  "Connect budget when no window can come: time for the client's first request to
   arrive, so the failure answers it."
  2000)

(defn- connect-plan
  "Port source, give-up message and connect options for the command line's port or port
   file, or for the caller's window when there is none."
  [port-or-port-file script-name]
  (if port-or-port-file
    {:port-source (port-or-port-file-source port-or-port-file)
     :give-up-message (constantly port-file-give-up-message)
     :options connect-policy/default-options}
    (let [{:keys [settled-without-window?] :as source} (caller-window-source script-name)]
      (assoc source :options (cond-> connect-policy/default-options
                               (settled-without-window?)
                               (assoc :connect/timeout-ms settled-timeout-ms))))))

(defn ^:export main [& args]

  (log-stderr :info "Running in node version: " (.-version process))

  (let [script-name (path/basename (nth (.-argv process) 1 "mcp-server.js"))
        [port-or-port-file host] (remove #(string/starts-with? % "--") args)
        {:keys [port-source give-up-message options]} (connect-plan port-or-port-file script-name)
        stdin (.-stdin process)
        stdin-line-queue (volatile! [])
        stdin-buffer (volatile! "")
        stdin-ended? (volatile! false)
        wait-phase-done? (volatile! false)
        cleanup-connect! (volatile! (fn []))
        abort-wait-phase! (fn []
                            (when-not @wait-phase-done?
                              (@cleanup-connect!)
                              (.exit process 0)))
        wait-handlers (attach-wait-phase-stdin!
                       stdin
                       {:line-queue stdin-line-queue
                        :buffer stdin-buffer
                        :ended? stdin-ended?
                        :on-wait-abort! abort-wait-phase!})]
    (-> (connect-with-retry!+ host options
                              {:port-source port-source
                               :give-up-message give-up-message
                               :stdin-ended? stdin-ended?
                               :cleanup-connect! cleanup-connect!
                               :queued-lines stdin-line-queue})
        (.then (fn [{:keys [socket port connect-host]}]
                 (vreset! wait-phase-done? true)
                 (detach-wait-phase-stdin! stdin wait-handlers)
                 (let [partial-remainder @stdin-buffer]
                   (vreset! stdin-buffer "")
                   (handle-socket socket)
                   (handle-stdin stdin socket {:initial-buffer partial-remainder})
                   (doseq [line @stdin-line-queue]
                     (forward-line-to-socket! socket line))
                   (vreset! stdin-line-queue [])
                   (log-stderr :info "Connected to MCP server on" connect-host "port" port)))))))
