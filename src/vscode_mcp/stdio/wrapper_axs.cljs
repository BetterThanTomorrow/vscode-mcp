(ns vscode-mcp.stdio.wrapper-axs
  "The stdio wrapper's decisions. `handle-action` takes the wrapper's state and one
   action, and returns the next state as `:uf/db` and the effects to perform, in order,
   as `:uf/fxs`. What comes from outside arrives in the action: the time, the window
   registry's live entries, a port read from a port file, a chunk from stdin or the socket."
  (:require
   [clojure.string :as string]
   [vscode-mcp.stdio.connect-policy :as connect-policy]
   [vscode-mcp.stdio.window-lookup :as window-lookup]))

(def port-file-give-up-message
  "MCP server not reachable within 60s — port file missing or server not accepting connections")

(def settled-timeout-ms
  "Connect budget when no window can come: time for the client's first request to
   arrive, so the failure answers it."
  2000)

;; Lines and messages

(defn- complete-lines
  "Returns `[lines remainder]` for `buffer`: its complete lines, trimmed and without the
   blank ones, and what follows the last newline."
  [buffer]
  (let [parts (string/split buffer #"\n" -1)]
    [(into [] (comp (map string/trim) (remove string/blank?)) (butlast parts))
     (last parts)]))

(defn- remaining-line
  "Returns a vector with what is left in `buffer` when a stream ends, as a last line,
   or an empty vector when that is blank."
  [buffer]
  (let [line (string/trim (or buffer ""))]
    (if (string/blank? line)
      []
      [line])))

(defn- request-ids
  "Ids of the JSON-RPC requests among `lines`."
  [lines]
  (keep (fn [line]
          (try
            (let [id (unchecked-get (js/JSON.parse line) "id")]
              (when (some? id)
                id))
            (catch :default _ nil)))
        lines))

(defn- error-line
  "Returns the JSON-RPC error with `code` and `message` as a line, for the request `id`
   when there is one."
  [code message id]
  (js/JSON.stringify (clj->js (cond-> (array-map :jsonrpc "2.0"
                                                 :error (array-map :code code
                                                                   :message message))
                                (some? id) (assoc :id id)))))

(defn- json-message? [line]
  (or (string/starts-with? line "{")
      (string/starts-with? line "[")))

;; From stdin to the socket

(defn- to-socket
  "The effects that send `lines` from stdin to the socket."
  [lines]
  (mapcat (fn [line]
            [[:log/fx.write :info "Complete message segment from stdin, sending to socket:" line]
             [:socket/fx.write line]])
          lines))

(defn- take-stdin-lines
  "Returns what `lines` from stdin give in `state`: queued while the wrapper waits for a
   server, sent to the socket once it is connected."
  [{:wrapper/keys [phase] :as state} lines]
  (case phase
    :waiting {:uf/db (update state :wrapper/queued-lines into lines)}
    :connected {:uf/db state
                :uf/fxs (to-socket lines)}
    {:uf/db state}))

(defn- stdin-data
  "Takes a `chunk` from stdin."
  [{:wrapper/keys [stdin-buffer] :as state} chunk]
  (let [[lines remainder] (complete-lines (str stdin-buffer chunk))]
    (take-stdin-lines (assoc state :wrapper/stdin-buffer remainder) lines)))

(defn- stdin-ended
  "Takes the end of stdin. A wrapper that still waits for a server stops waiting and
   exits cleanly. A connected one sends what was left of the last line."
  [{:wrapper/keys [phase stdin-buffer] :as state}]
  (let [state (assoc state :wrapper/stdin-buffer "")
        lines (remaining-line stdin-buffer)]
    (if (= :waiting phase)
      {:uf/db (assoc state :wrapper/phase :ended)
       :uf/fxs [[:connect/fx.cancel]
                [:process/fx.exit 0]]}
      (take-stdin-lines state lines))))

;; Connecting

(defn- attempt-effects
  "The effects that start a connect attempt, by where the port comes from: given, in a
   port file, or in the entry of the caller's window."
  [{:wrapper/keys [script-name]
    :connect/keys [port port-file host search]}]
  [[:log/fx.write :debug "Attempting MCP server connection…"]
   (cond
     port [:connect/fx.connect port (connect-policy/connect-host host)]
     port-file [:connect/fx.read-port-file port-file host]
     :else [:connect/fx.read-entries search script-name])])

(defn- port-source
  "Where the port comes from, for the command line's `port-or-port-file` and `host`, or
   for the caller's window found by `search` when there is none."
  [port-or-port-file host search]
  (cond
    (nil? port-or-port-file) {:connect/search search}
    (parse-long port-or-port-file) {:connect/port (parse-long port-or-port-file)
                                    :connect/host host}
    :else {:connect/port-file port-or-port-file
           :connect/host host}))

(defn- start
  "Starts the wrapper: it waits for a server, and makes its first connect attempt.
   Without a port or a port file it looks for the caller's window among `entries`, and
   when no window can come it waits only long enough to answer the client's first request."
  [_state {:wrapper/keys [script-name]
           :connect/keys [port-or-port-file host search entries now-ms]}]
  (let [caller-window? (nil? port-or-port-file)
        settled? (and caller-window?
                      (window-lookup/settled-without-window? entries search script-name))
        state (merge {:wrapper/phase :waiting
                      :wrapper/script-name script-name
                      :wrapper/queued-lines []
                      :wrapper/stdin-buffer ""
                      :wrapper/socket-buffer ""
                      :connect/start-ms now-ms
                      :connect/entries entries
                      :connect/options (cond-> connect-policy/default-options
                                         settled? (assoc :connect/timeout-ms settled-timeout-ms))}
                     (port-source port-or-port-file host search))]
    {:uf/db state
     :uf/fxs (concat (when caller-window?
                       [[:log/fx.write :info "Looking for the caller's window:" (window-lookup/caller-search-text search)]])
                     [[:log/fx.write :info "waiting for MCP server…"]]
                     (attempt-effects state))}))

(defn- attempt
  "Makes a connect attempt, when the wrapper still waits for a server."
  [{:wrapper/keys [phase] :as state}]
  (when (= :waiting phase)
    {:uf/fxs (attempt-effects state)}))

(defn- give-up-message
  "What the wrapper says when it gives up: for a port or a port file that the server
   wasn't reachable, and for the caller's window what was looked for and found."
  [{:wrapper/keys [script-name]
    :connect/keys [search entries]}]
  (if search
    (window-lookup/failure-message entries search script-name)
    port-file-give-up-message))

(defn- give-up
  "Stops waiting: each queued request gets an error, or one error without an id goes out
   when nothing is queued, and the wrapper exits with an error."
  [{:wrapper/keys [queued-lines] :as state}]
  (let [message (give-up-message state)]
    {:uf/db (assoc state :wrapper/phase :gave-up)
     :uf/fxs (concat [[:connect/fx.cancel]
                      [:log/fx.write :error message]]
                     (map (fn [id]
                            [:stdout/fx.write (error-line -32001 message id)])
                          (or (seq (request-ids queued-lines)) [nil]))
                     [[:process/fx.exit 1]])}))

(defn- attempt-failed
  "Takes a connect attempt that failed for `reason` at `now-ms`: the wrapper tries again
   after the interval, or gives up when its connect budget is spent."
  [{:wrapper/keys [phase]
    :connect/keys [start-ms options] :as state}
   {:connect/keys [now-ms reason]}]
  (when (= :waiting phase)
    (let [{:connect/keys [action delay-ms elapsed-ms]} (connect-policy/attempt-decision
                                                        {:connect/start-ms start-ms
                                                         :connect/now-ms now-ms}
                                                        options)
          log [:log/fx.write :debug "Connect attempt failed:" reason "elapsed-ms" elapsed-ms "action" action]]
      (if (= :give-up action)
        (update (give-up state) :uf/fxs #(cons log %))
        {:uf/fxs [log
                  [:connect/fx.retry-after delay-ms]]}))))

(defn- entries-read
  "Takes the window registry's live `entries`, read for a connect attempt: the port file
   of the wrapper's own server in the caller's window is read, or the attempt has failed
   when there is no such entry yet."
  [{:wrapper/keys [phase script-name]
    :connect/keys [search] :as state}
   {:connect/keys [entries now-ms]}]
  (when (= :waiting phase)
    (let [state (assoc state :connect/entries entries)
          {:keys [portFilePath host]} (get-in (window-lookup/target entries search script-name)
                                              [:target/entry :mcp])]
      (if portFilePath
        {:uf/db state
         :uf/fxs [[:connect/fx.read-port-file portFilePath host]]}
        (merge {:uf/db state}
               (attempt-failed state {:connect/now-ms now-ms
                                      :connect/reason "port unavailable"}))))))

(defn- port-read
  "Takes the `port` read from a port file, nil when the file had none: the wrapper
   connects to it on `host`, or the attempt has failed."
  [{:wrapper/keys [phase] :as state}
   {:connect/keys [port host now-ms]}]
  (when (= :waiting phase)
    (if port
      {:uf/fxs [[:connect/fx.connect port (connect-policy/connect-host host)]]}
      (attempt-failed state {:connect/now-ms now-ms
                             :connect/reason "port unavailable"}))))

(defn- connected
  "Takes a connect to `port` on `host`: the wrapper relays from here on, starting with
   the lines that were queued. A connect that comes after the wrapper stopped waiting
   is closed."
  [{:wrapper/keys [phase queued-lines] :as state}
   {:connect/keys [port host]}]
  (if (= :waiting phase)
    {:uf/db (assoc state
                   :wrapper/phase :connected
                   :wrapper/queued-lines [])
     :uf/fxs (concat [[:socket/fx.relay]]
                     (to-socket queued-lines)
                     [[:log/fx.write :info "Connected to MCP server on" host "port" port]])}
    {:uf/fxs [[:connect/fx.cancel]]}))

;; From the socket to stdout

(defn- to-stdout
  "The effects that send `lines` from the socket to stdout. A line that isn't JSON is
   left out."
  [lines]
  (mapcat (fn [line]
            (if (json-message? line)
              [[:log/fx.write :info "Sending to stdout:" line]
               [:stdout/fx.write line]]
              [[:log/fx.write :warn "Filtered potential non-JSON output from socket:" line]]))
          lines))

(defn- socket-data
  "Takes a `chunk` from the socket."
  [{:wrapper/keys [socket-buffer] :as state} chunk]
  (let [[lines remainder] (complete-lines (str socket-buffer chunk))]
    {:uf/db (assoc state :wrapper/socket-buffer remainder)
     :uf/fxs (to-stdout lines)}))

(defn- socket-ended
  "Takes the end of the socket's stream: what was left of the last line goes to stdout."
  [{:wrapper/keys [socket-buffer] :as state}]
  {:uf/db (assoc state :wrapper/socket-buffer "")
   :uf/fxs (to-stdout (remaining-line socket-buffer))})

(defn- socket-error
  "Takes an error on the connected socket, with its `message`: the client gets an error,
   and the wrapper exits with one."
  [state message]
  {:uf/db (assoc state :wrapper/phase :ended)
   :uf/fxs [[:log/fx.write :error "Socket error:" message]
            [:stdout/fx.write (error-line -32000 (str "Server connection error: " message) nil)]
            [:process/fx.exit 1]]})

(defn- socket-closed
  "Takes the close of the connected socket: the wrapper exits, with an error when the
   close came from one."
  [state had-error?]
  {:uf/db (assoc state :wrapper/phase :ended)
   :uf/fxs [[:log/fx.write :info (if had-error?
                                   "Socket closed due to transmission error."
                                   "Socket connection closed cleanly.")]
            [:process/fx.exit (if had-error? 1 0)]]})

(def actions
  {:wrapper/ax.start #'start
   :stdin/ax.data #'stdin-data
   :stdin/ax.ended #'stdin-ended
   :connect/ax.attempt #'attempt
   :connect/ax.entries-read #'entries-read
   :connect/ax.port-read #'port-read
   :connect/ax.failed #'attempt-failed
   :connect/ax.connected #'connected
   :socket/ax.data #'socket-data
   :socket/ax.ended #'socket-ended
   :socket/ax.error #'socket-error
   :socket/ax.closed #'socket-closed})

(defn handle-action
  "Returns `{:uf/db next-state :uf/fxs effects}` for `action` on `state`. An action
   that the action table doesn't have changes nothing, and is logged as an error."
  [state [action-name & args]]
  (if-let [handler (get actions action-name)]
    (let [{:uf/keys [db fxs]} (apply handler state args)]
      {:uf/db (or db state)
       :uf/fxs (vec fxs)})
    {:uf/db state
     :uf/fxs [[:log/fx.write :error "Unknown action:" (pr-str action-name)]]}))
