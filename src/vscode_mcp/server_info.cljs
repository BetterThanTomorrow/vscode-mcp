(ns vscode-mcp.server-info
  "Internal to `vscode-mcp.server` — not part of the consumer API.")

(defn merge-started-server-info
  [runtime-options server-info+ {:server/keys [port-file-uri host]}]
  (merge runtime-options server-info+
         (cond-> {:server/host host}
           port-file-uri (assoc :server/port-file-uri port-file-uri))))
