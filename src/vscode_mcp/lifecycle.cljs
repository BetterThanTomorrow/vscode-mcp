(ns vscode-mcp.lifecycle
  "Lifecycle state and config helpers — no VS Code API touched here."
  (:require
   [vscode-mcp.cursor-config :as cursor-config]
   [vscode-mcp.lifecycle-turns :as lifecycle-turns]
   [vscode-mcp.stdio-config :as stdio-config]))

(defn create-config
  "Returns the config for `opts`, with the defaults for what `opts` leaves out.
   `:server/name` names the server, and `:wrapper/script-relative-path` is the stdio
   wrapper's script in the extension."
  [opts]
  (merge {:mcp/auto-start? false
          :mcp/auto-register? true
          :mcp/auto-register-eca? false
          :server/host stdio-config/default-host
          :registry/enabled? false
          :registry/heartbeat-ms 30000
          :registry/debounce-ms 1000}
         opts))

(def stopped-server-state
  "The state of a server that doesn't run, as the library reports a server's state: plain data."
  {:server/running? false
   :server/starting? false
   :server/stopping? false
   :server/host nil
   :server/assigned-port nil
   :server/instance-slug nil
   :server/port-file-path nil
   :server/error nil
   :lifecycle/registered-name nil
   :lifecycle/registration-error nil})

(defn registration-error
  "Returns what a Cursor registration `failure` is in the server's state: its reason,
   and the message of what it threw when it threw."
  [{:keys [reason message]}]
  (cond-> {:reason (or reason :registration-failed)}
    message (assoc :message message)))

(defn registration-intent
  "Pure plan for the next registerServer call. When already registered, the
   current name is retired and generation increments so Cursor always sees a
   fresh name within the session."
  [state base-name instance-slug]
  (let [registered (:lifecycle/registered-name state)
        generation (:lifecycle/generation state 0)]
    (if registered
      (let [generation' (inc generation)]
        {:register/unregister-name registered
         :register/generation generation'
         :register/register-name (cursor-config/slugged-server-name
                                   base-name instance-slug generation')})
      {:register/unregister-name nil
       :register/generation generation
       :register/register-name (cursor-config/slugged-server-name
                                 base-name instance-slug generation)})))

(def silent-stop-wait-ms
  "How long the silent stop at deactivation waits for a call before it that doesn't
   settle, from when the stop is called. VS Code gives deactivation a few seconds
   before it ends the Extension Host, so the stop waits less than that, and still has
   time to run."
  3000)

(defn stop-options
  "Returns the options of a stop as data, from what `stop!+` was given: a map with
   `:lifecycle/silent?`, or whether the stop is silent."
  [options]
  {:lifecycle/silent? (boolean (if (map? options)
                                 (:lifecycle/silent? options)
                                 options))})

(defn turn-options
  "How a call for `config`'s server waits for the one before it, and logs through
   `:mcp/on-log` that it runs anyway when it waited it out. A call waits
   `lifecycle-turns/default-wait-ms` at most from when the call before it began. A
   stop with `stop-options` that make it silent, the one at deactivation, waits
   `silent-stop-wait-ms` at most from when it is called, also when the call before
   it still waits for its own turn."
  ([config]
   (turn-options config nil))
  ([{server-name :server/name :mcp/keys [on-log]} {:lifecycle/keys [silent?]}]
   (let [wait-ms (if silent?
                   silent-stop-wait-ms
                   lifecycle-turns/default-wait-ms)]
     {:turns/wait-ms wait-ms
      :turns/from-call? (boolean silent?)
      :turns/on-waited-out (fn []
                             (when on-log
                               (on-log :warn "[MCP] A lifecycle call for" server-name
                                       "hasn't settled after" wait-ms "ms; the next one runs anyway")))})))
