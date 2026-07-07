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
  [opts]
  (merge {:mcp/auto-start? false
          :mcp/auto-register? true
          :server/host stdio-config/default-host}
         opts))

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
