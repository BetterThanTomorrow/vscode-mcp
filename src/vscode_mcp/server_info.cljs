(ns vscode-mcp.server-info
  "Internal to `vscode-mcp.server` — not part of the consumer API.")

(defn merge-started-server-info
  "Combines runtime options with socket server info from start-socket-server!+.
   :server/request-port and :server/assigned-port are distinct keys."
  [runtime-options server-info+ {:server/keys [port-file-uri host]}]
  (merge runtime-options server-info+
         (cond-> {:server/host host}
           port-file-uri (assoc :server/port-file-uri port-file-uri))))

(defn discovery-request?
  "True when an MCP JSON-RPC method counts as client discovery."
  [method]
  (contains? #{"tools/list" "resources/list"} method))
