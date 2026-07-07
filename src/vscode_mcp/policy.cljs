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

(defn should-register-on-start?
  [{:mcp/keys [auto-register? cursor-available? port-file-present?]
    :lifecycle/keys [skip-register?]}]
  (and (should-register-with-cursor? {:mcp/auto-register? auto-register?
                                      :mcp/cursor-available? cursor-available?
                                      :mcp/port-file-present? port-file-present?})
       (not skip-register?)))
