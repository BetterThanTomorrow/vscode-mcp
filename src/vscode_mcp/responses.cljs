(ns vscode-mcp.responses)

(defn text-response
  "JSON-RPC success response with JSON-stringified text content."
  [id data]
  {:jsonrpc "2.0"
   :id id
   :result {:content [{:type "text"
                       :text (js/JSON.stringify data)}]}})

(defn clj-response
  "JSON-RPC success response with clj->js JSON-stringified text content."
  [id data]
  {:jsonrpc "2.0"
   :id id
   :result {:content [{:type "text"
                       :text (js/JSON.stringify (clj->js data))}]}})

(defn content-response
  "JSON-RPC success response with pre-built content array."
  [id content]
  {:jsonrpc "2.0"
   :id id
   :result {:content content}})

(defn success-response
  "Standard JSON-RPC success response with a generic result object.
   Used for protocol methods like initialize, tools/list, and resources/list."
  [id result]
  {:jsonrpc "2.0"
   :id id
   :result result})

(defn error-response
  "JSON-RPC error response."
  [id code message]
  {:jsonrpc "2.0"
   :id id
   :error {:code code :message message}})
