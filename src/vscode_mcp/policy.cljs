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
