(ns vscode-mcp.stdio-wrapper-process-test
  "Starts the stdio wrapper as a process, against a socket server in this process, for
   what depends on timing: what the wrapper does while it waits for a server, when it
   gives up, and when the connection breaks."
  (:require
   ["child_process" :as child-process]
   ["fs" :as fs]
   ["net" :as net]
   ["os" :as os]
   ["path" :as path]
   [cljs.test :refer [async deftest is]]
   [clojure.string :as string]))

(def ^:private wrapper-script
  "The wrapper as `bb test` compiles it."
  (path/resolve "out" "stdio-wrapper.js"))

(def ^:private vscode-variables
  "The variables the wrapper finds the caller's window by."
  ["VSCODE_PID" "VSCODE_MCP_WINDOW_ID" "VSCODE_MCP_EXTENSION_HOST" "VSCODE_CRASH_REPORTER_PROCESS_TYPE"])

(defn- temp-home!
  "Creates a directory for the wrapper's home, where it finds no window registry."
  []
  (fs/realpathSync (fs/mkdtempSync (path/join (os/tmpdir) "vscode-mcp-wrapper-test-"))))

(defn- wrapper-env
  "This process's environment with `home` for the home directory and without VS Code's
   variables, so the wrapper finds no window."
  [home]
  (let [env (js/Object.assign #js {} js/process.env #js {:HOME home :USERPROFILE home})]
    (doseq [variable vscode-variables]
      (js-delete env variable))
    env))

(defn- start-wrapper!
  "Starts the wrapper with `args` and `home` for its home and working directory. Returns
   the process as `:child`, and `:!run` with what it has written to stdout and its exit code."
  [args home]
  (let [^js child (child-process/spawn js/process.execPath
                                       (into-array (into [wrapper-script "--min-log-level=error"] args))
                                       #js {:env (wrapper-env home)
                                            :cwd home
                                            :stdio #js ["pipe" "pipe" "ignore"]})
        !run (atom {:stdout "" :exit nil})]
    (.setEncoding (.-stdout child) "utf8")
    (.on (.-stdout child) "data" (fn [chunk]
                                   (swap! !run update :stdout str chunk)))
    (.on (.-stdin child) "error" (fn [_]))
    (.on child "exit" (fn [code _signal]
                        (swap! !run assoc :exit code)))
    {:child child
     :!run !run}))

(defn- send!
  "Writes `messages` to the wrapper's stdin, a line each."
  [{:keys [^js child]} messages]
  (doseq [message messages]
    (.write (.-stdin child) (str (js/JSON.stringify (clj->js message)) "\n"))))

(defn- responses
  "The messages the wrapper has written to stdout."
  [{:keys [!run]}]
  (->> (string/split-lines (:stdout @!run))
       (remove string/blank?)
       (mapv #(js->clj (js/JSON.parse %) :keywordize-keys true))))

(defn- exit-code [{:keys [!run]}]
  (:exit @!run))

(defn- wait-for+
  "Returns a promise that resolves when `(pred)` is truthy, and rejects with `message`
   after `timeout-ms`."
  [pred timeout-ms message]
  (js/Promise.
   (fn [resolve reject]
     (let [start-ms (js/Date.now)
           check (fn check []
                   (cond
                     (pred) (resolve true)
                     (< timeout-ms (- (js/Date.now) start-ms)) (reject (js/Error. message))
                     :else (js/setTimeout check 20)))]
       (check)))))

(defn- start-server!+
  "Starts a socket server on a free loopback port. `on-line` is called with the socket
   and each line the server gets. Returns a promise of `{:server … :port … :!lines …}`."
  [on-line]
  (js/Promise.
   (fn [resolve _reject]
     (let [!lines (atom [])
           ^js server (net/createServer
                       (fn [^js socket]
                         (let [!buffer (atom "")]
                           (.setEncoding socket "utf8")
                           (.on socket "error" (fn [_]))
                           (.on socket "data"
                                (fn [chunk]
                                  (let [parts (string/split (str @!buffer chunk) #"\n" -1)]
                                    (reset! !buffer (last parts))
                                    (doseq [line (butlast parts)]
                                      (swap! !lines conj line)
                                      (on-line socket line))))))))]
       (.listen server 0 "127.0.0.1"
                (fn []
                  (resolve {:server server
                            :port (.-port (.address server))
                            :!lines !lines})))))))

(defn- answer!
  "Answers the request in `line` on `socket` with the request's method."
  [^js socket line]
  (let [{:keys [id method]} (js->clj (js/JSON.parse line) :keywordize-keys true)]
    (when (some? id)
      (.write socket (str (js/JSON.stringify (clj->js {:jsonrpc "2.0" :id id :result {:answered method}})) "\n")))))

(defn- finish!
  "Ends a test: stops the `wrappers` and the `server`, removes `home`, and calls `done`.
   A home that a stopping wrapper still holds, as on Windows, is left for the system's
   own cleanup of the temp directory."
  [{:keys [wrappers ^js server home done]}]
  (doseq [{:keys [^js child]} wrappers]
    (.kill child))
  (when server
    (.close server))
  (try
    (fs/rmSync home #js {:recursive true :force true :maxRetries 5 :retryDelay 50})
    (catch :default _))
  (done))

(defn- fails [e]
  (is (nil? e) (str "The test's steps run: " e)))

(deftest stdin-ends-while-the-wrapper-waits
  (async done
    (let [home (temp-home!)
          port-file (path/join home "no-such.port")
          silent (start-wrapper! [port-file "127.0.0.1"] home)
          with-request (start-wrapper! [port-file "127.0.0.1"] home)]
      (send! with-request [{:jsonrpc "2.0" :id 1 :method "initialize"}])
      (js/setTimeout (fn []
                       (.end (.-stdin (:child silent)))
                       (.end (.-stdin (:child with-request))))
                     700)
      (-> (wait-for+ #(and (some? (exit-code silent))
                           (some? (exit-code with-request)))
                     10000
                     "The wrappers didn't exit after stdin ended")
          (.then (fn [_]
                   (is (= 0 (exit-code silent))
                       "A wrapper whose stdin ends while it waits exits cleanly")
                   (is (= [] (responses silent))
                       "and writes nothing")
                   (is (= 0 (exit-code with-request))
                       "With a request queued it exits cleanly too")
                   (is (= [] (responses with-request))
                       "and the request gets no answer")))
          (.catch fails)
          (.finally #(finish! {:wrappers [silent with-request] :home home :done done}))))))

(deftest the-wrapper-gives-up
  (async done
    (let [home (temp-home!)
          with-requests (start-wrapper! [] home)
          without-requests (start-wrapper! [] home)]
      (send! with-requests [{:jsonrpc "2.0" :id 1 :method "initialize"}
                            {:jsonrpc "2.0" :method "notifications/initialized"}
                            {:jsonrpc "2.0" :id "b" :method "tools/list"}])
      (-> (wait-for+ #(and (some? (exit-code with-requests))
                           (some? (exit-code without-requests)))
                     15000
                     "The wrappers didn't give up")
          (.then (fn [_]
                   (is (= 1 (exit-code with-requests))
                       "A wrapper that finds no window exits with an error")
                   (is (= [1 "b"] (mapv :id (responses with-requests)))
                       "Each queued request gets an answer, and the notification none")
                   (is (every? #(= -32001 (get-in % [:error :code])) (responses with-requests))
                       "The answers are errors")
                   (is (every? #(string/starts-with? (get-in % [:error :message])
                                                     "No window found for stdio-wrapper.js.")
                               (responses with-requests))
                       (str "that say no window was found: " (pr-str (responses with-requests))))
                   (is (= 1 (exit-code without-requests)))
                   (is (= [-32001] (mapv #(get-in % [:error :code]) (responses without-requests)))
                       "With nothing queued there is one error")
                   (is (not (contains? (first (responses without-requests)) :id))
                       "without an id")))
          (.catch fails)
          (.finally #(finish! {:wrappers [with-requests without-requests] :home home :done done}))))))

(deftest a-server-that-appears-on-a-late-attempt
  (async done
    (let [home (temp-home!)
          port-file (path/join home "late.port")
          wrapper (start-wrapper! [port-file "127.0.0.1"] home)
          !server (atom nil)]
      (send! wrapper [{:jsonrpc "2.0" :id 1 :method "initialize"}])
      (-> (js/Promise. (fn [resolve _reject]
                         (js/setTimeout resolve 800)))
          (.then (fn [_]
                   (start-server!+ answer!)))
          (.then (fn [{:keys [port] :as started}]
                   (reset! !server started)
                   (fs/writeFileSync port-file (str port))
                   (wait-for+ #(= 1 (count (responses wrapper)))
                              10000
                              "The queued request wasn't answered")))
          (.then (fn [_]
                   (is (= [{:jsonrpc "2.0" :id 1 :result {:answered "initialize"}}]
                          (responses wrapper))
                       "The request sent while the wrapper waited is answered once the server is there")
                   (send! wrapper [{:jsonrpc "2.0" :id 2 :method "tools/list"}])
                   (wait-for+ #(= 2 (count (responses wrapper)))
                              10000
                              "The request after the connect wasn't answered")))
          (.then (fn [_]
                   (is (= {:jsonrpc "2.0" :id 2 :result {:answered "tools/list"}}
                          (second (responses wrapper)))
                       "and so is a request sent after the connect")
                   (is (= [1 2]
                          (mapv #(:id (js->clj (js/JSON.parse %) :keywordize-keys true))
                                @(:!lines @!server)))
                       "The server got each request once, in order")
                   (.end (.-stdin (:child wrapper)))
                   (is (nil? (exit-code wrapper))
                       "The wrapper runs while the connection is open")))
          (.catch fails)
          (.finally #(finish! {:wrappers [wrapper] :server (:server @!server) :home home :done done}))))))

(deftest the-server-closes-the-connection
  (async done
    (let [home (temp-home!)
          port-file (path/join home "closing.port")
          !server (atom nil)
          !wrapper (atom nil)]
      (-> (start-server!+ (fn [^js socket line]
                            (answer! socket line)
                            (.end socket)))
          (.then (fn [{:keys [port] :as started}]
                   (reset! !server started)
                   (fs/writeFileSync port-file (str port))
                   (reset! !wrapper (start-wrapper! [port-file "127.0.0.1"] home))
                   (send! @!wrapper [{:jsonrpc "2.0" :id 1 :method "initialize"}])
                   (wait-for+ #(some? (exit-code @!wrapper))
                              10000
                              "The wrapper didn't exit when the server closed the connection")))
          (.then (fn [_]
                   (is (= [{:jsonrpc "2.0" :id 1 :result {:answered "initialize"}}]
                          (responses @!wrapper))
                       "What the server wrote before it closed reaches stdout")
                   (is (= 0 (exit-code @!wrapper))
                       "and the wrapper exits cleanly")))
          (.catch fails)
          (.finally #(finish! {:wrappers (keep identity [@!wrapper]) :server (:server @!server) :home home :done done}))))))

(deftest a-socket-error-after-the-connect
  (async done
    (let [home (temp-home!)
          port-file (path/join home "resetting.port")
          !server (atom nil)
          !wrapper (atom nil)]
      (-> (start-server!+ (fn [^js socket _line]
                            (.resetAndDestroy socket)))
          (.then (fn [{:keys [port] :as started}]
                   (reset! !server started)
                   (fs/writeFileSync port-file (str port))
                   (reset! !wrapper (start-wrapper! [port-file "127.0.0.1"] home))
                   (send! @!wrapper [{:jsonrpc "2.0" :id 1 :method "initialize"}])
                   (wait-for+ #(some? (exit-code @!wrapper))
                              10000
                              "The wrapper didn't exit when the connection was reset")))
          (.then (fn [_]
                   (is (= 1 (exit-code @!wrapper))
                       "A wrapper whose connection is reset exits with an error")
                   (is (= [-32000] (mapv #(get-in % [:error :code]) (responses @!wrapper)))
                       "after one error on stdout")
                   (is (string/starts-with? (str (get-in (first (responses @!wrapper)) [:error :message]))
                                            "Server connection error: ")
                       (str "that says the connection failed: " (pr-str (responses @!wrapper))))
                   (is (not (contains? (first (responses @!wrapper)) :id))
                       "The error has no id")))
          (.catch fails)
          (.finally #(finish! {:wrappers (keep identity [@!wrapper]) :server (:server @!server) :home home :done done}))))))
