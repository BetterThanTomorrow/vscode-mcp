# vscode-mcp

A ClojureScript library for exposing an MCP (Model Context Protocol) server from within a VS Code Extension Host.

The library is meant to be used for VS Code extensions that want to provide AI tools and resources both via Copilot and via MCP, with zero config for Cursor. It manages the TCP socket lifecycle and provides zero-config auto-registration for Cursor.

The libray works by:

1. Spawning a local TCP socket server inside the Extension Host.
2. Providing a pure Node.js `stdio` wrapper script that clients run. The wrapper relays streams to the TCP socket. This is because MCP clients expect to spawn an executable and communicate over standard input/output streams (`stdio`), but VS Code extensions live inside an already started Extension Host.
3. Automatically registering the server configuration with Cursor using its undocumented `vscode.cursor.mcp.registerServer` API.

## Usage

### 1. Add the Dependency

Add `vscode-mcp` to your project's dependencies (in `deps.edn` via `:git/url` or `:local/root`).

### 2. Configure the Stdio Wrapper Build

Consumers must bundle the provided `stdio` wrapper script so the MCP client has a physical `.js` file to execute.

Add a build target to your `shadow-cljs.edn` that compiles the library's generic wrapper namespace into your distribution directory:

```edn
:stdio-wrapper {:target :node-script
                :main vscode-mcp.stdio.wrapper/main
                :output-to "dist/mcp-server.js"}
```

### 3. Implement the Request Handler

The server is stateless and protocol-agnostic. You must provide an `on-request` callback that receives parsed JSON-RPC requests (e.g., `initialize`, `tools/list`, `tools/call`) and returns the JSON-RPC response (or a Promise resolving to it).

```clojure
(require '[vscode-mcp.server :as mcp-server])

(defn handle-mcp-request [{:keys [method params id] :as request}]
  (case method
    "initialize" {:jsonrpc "2.0" :id id :result {:capabilities {:tools {}}}}
    ;; Handle tools/list, tools/call, resources/list, etc.
    {:jsonrpc "2.0" :id id :error {:code -32601 :message "Method not found"}}))
```

### 4. Start the Server & Auto-Register

During your extension's `activate` phase, start the server and register it with Cursor:

```clojure
(require '[vscode-mcp.cursor :as mcp-cursor])
(require '["path" :as path])

(defn activate [^js context]
  (let [wrapper-path (path/join (.-extensionPath context) "dist" "mcp-server.js")]
    (-> (mcp-server/start-server!+ {:on-request handle-mcp-request
                                    :port 0}) ;; Use 0 to assign a random available port
        (.then (fn [server-info]
                 (mcp-cursor/register-and-reload-mcp-client!+
                   context
                   (assoc server-info
                          :server/name "my-extension-mcp"
                          :server/wrapper-path wrapper-path)))))))
```

When stopping your extension in `deactivate`, gracefully shut down the server:

```clojure
(mcp-server/stop-server!+ server-info)
```
