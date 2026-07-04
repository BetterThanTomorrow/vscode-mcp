(ns vscode-mcp.lifecycle
  "Lifecycle state and config helpers — no VS Code API touched here.
   `init-state`, `running?`, `server-info`, `create-config`, and
   `should-call-register-server?` are re-exported from `vscode-mcp.core`,
   which is the namespace consumers should actually require.

   `port-file-present?` is internal — used only by `vscode-mcp.core`
   itself, not re-exported, not part of the consumer API."
  (:require
   [vscode-mcp.stdio-config :as stdio-config]))

(defn init-state
  "The zero-value lifecycle state. Consumers own storing/updating this data;
   `vscode-mcp.core` holds no atom of its own."
  []
  {:lifecycle/server-info nil
   :lifecycle/cursor-registered? false
   :lifecycle/cursor-register-called? false
   :lifecycle/starting? false
   :lifecycle/stopping? false})

(defn running?
  [state]
  (boolean (:lifecycle/server-info state)))

(defn server-info
  [state]
  (:lifecycle/server-info state))

(defn create-config
  "Validates/defaults a lifecycle config map. Pure — returns data, not an instance."
  [opts]
  (merge {:mcp/auto-start? false
          :mcp/auto-register? true
          :server/host stdio-config/default-host}
         opts))

(defn port-file-present?
  [server-info]
  (boolean (seq (some-> server-info :server/port-file-uri (unchecked-get "fsPath")))))

(defn should-call-register-server?
  "Cursor registration dedupe: register when allowed by policy and dedupe flags
   permit it. Manual (`silent?` false/nil) and `:lifecycle/force-register?`
   always attempt registration — needed after stop+unregister when lifecycle
   state still says registered, and for the explicit register-with-Cursor command
   when auto-register is off."
  [state {:lifecycle/keys [silent? force-register?]} register-allowed?]
  (let [cleared (if (and silent? (not force-register?))
                  state
                  (dissoc state :lifecycle/cursor-register-called?))
        needs-register? (or force-register?
                            (not silent?)
                            (not (:lifecycle/cursor-registered? state)))]
    (and (or register-allowed? force-register?)
         needs-register?
         (or force-register?
             (not (:lifecycle/cursor-register-called? cleared))))))
