(ns vscode-mcp.policy)

(defn should-auto-start?
  [{:mcp/keys [auto-start? auto-register? cursor-available?]}]
  (or auto-start?
      (and auto-register? cursor-available?)))

(defn should-register-with-cursor?
  [{:mcp/keys [auto-register? cursor-available? port-file-present?]}]
  (and auto-register?
       cursor-available?
       port-file-present?))

(defn should-reload-client?
  "Whether to call mcp.reloadClient after registerServer.
   Manual start (silent? false or missing) always reloads. Silent activations
   reload when the server was just cold-started (a client spawned by Cursor
   before the server was up needs a kick to recover from its failed connect),
   when the registration config changed, or after unregister+register."
  [{:lifecycle/keys [silent?]
    :cursor/keys [server-cold-started? config-changed? pending-reload-after-unregister?]}]
  (or (not silent?)
      server-cold-started?
      config-changed?
      pending-reload-after-unregister?))
