(ns vscode-mcp.lifecycle.state
  "Lifecycle state and config helpers — no VS Code API touched here.
   Re-exported from `vscode-mcp.lifecycle`, which is the namespace consumers
   should actually require."
  (:require
   [vscode-mcp.stdio-config :as stdio-config]))

(defn init-state
  "The zero-value lifecycle state. Consumers own storing/updating this data;
   `vscode-mcp.lifecycle` holds no atom of its own."
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
  "Cursor registration dedupe: register when allowed by policy,
   not already registered, and not already called this activation — unless a
   manual (non-silent) start clears the called flag first."
  [state {:lifecycle/keys [silent?]} register-allowed?]
  (and register-allowed?
       (not (:lifecycle/cursor-registered? state))
       (not (:lifecycle/cursor-register-called?
             (if silent? state (dissoc state :lifecycle/cursor-register-called?))))))
