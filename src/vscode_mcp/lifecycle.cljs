(ns vscode-mcp.lifecycle
  "Lifecycle state and config helpers — no VS Code API touched here."
  (:require
   [vscode-mcp.cursor-config :as cursor-config]
   [vscode-mcp.stdio-config :as stdio-config]))

(defn init-state
  []
  {:lifecycle/server-info nil
   :lifecycle/registered-name nil
   :lifecycle/generation 0
   :lifecycle/starting? false
   :lifecycle/stopping? false})

(defn running?
  [state]
  (boolean (:lifecycle/server-info state)))

(defn server-info
  [state]
  (:lifecycle/server-info state))

(defn cursor-registered?
  [state]
  (some? (:lifecycle/registered-name state)))

(defn create-config
  "Returns the config for `opts`, with the defaults for what `opts` leaves out. The
   server's name and the wrapper's script are `:server/name` and
   `:wrapper/script-relative-path`. `:cursor/server-name` and
   `:cursor/script-relative-path` name the same two, and each pair is filled in from
   the other."
  [opts]
  (let [server-name (or (:server/name opts) (:cursor/server-name opts))
        script-relative-path (or (:wrapper/script-relative-path opts) (:cursor/script-relative-path opts))]
    (cond-> (merge {:mcp/auto-start? false
                    :mcp/auto-register? true
                    :mcp/auto-register-eca? false
                    :server/host stdio-config/default-host
                    :registry/enabled? false
                    :registry/heartbeat-ms 30000
                    :registry/debounce-ms 1000}
                   opts)
      server-name (assoc :server/name server-name
                         :cursor/server-name server-name)
      script-relative-path (assoc :wrapper/script-relative-path script-relative-path
                                  :cursor/script-relative-path script-relative-path))))

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

(defn server-facts
  "Returns what the lifecycle `state` says about the server, as plain data: whether it
   runs, where it listens, its window's slug, its port file, and its name in Cursor."
  [state]
  (let [{:server/keys [host assigned-port instance-slug port-file-uri]} (server-info state)]
    {:server/running? (running? state)
     :server/host host
     :server/assigned-port assigned-port
     :server/instance-slug instance-slug
     :server/port-file-path (some-> port-file-uri (unchecked-get "fsPath"))
     :lifecycle/registered-name (:lifecycle/registered-name state)}))

(defn registration-error
  "Returns what a Cursor registration `failure` is in the server's state: its reason,
   and the message of what it threw when it threw."
  [{:keys [reason error]}]
  (cond-> {:reason (or reason :registration-failed)}
    error (assoc :message (or (ex-message error) (str error)))))

(defn changed-server-state
  "Returns the server's `state` with `changes` in it. A server that begins to start has
   no error from an earlier start."
  [state changes]
  (cond-> (merge state changes)
    (:server/starting? changes) (assoc :server/error nil)))

(defn port-file-present?
  [server-info]
  (boolean (seq (some-> server-info :server/port-file-uri (unchecked-get "fsPath")))))

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
