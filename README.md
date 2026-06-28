# vscode-mcp

A [ClojureScript](https://clojurescript.org) library for exposing an MCP (Model Context Protocol) server from within a VS Code Extension Host.

The library is meant to be used for VS Code extensions that want to provide AI tools and resources both via Copilot and via MCP, with zero config for Cursor. It manages the TCP socket lifecycle and provides zero-config auto-registration for Cursor.

The libray works by:

1. Spawning a local TCP socket server inside the Extension Host.
2. Providing a pure Node.js `stdio` wrapper script that clients run. The wrapper relays streams to the TCP socket. This is because MCP clients expect to spawn an executable and communicate over standard input/output streams (`stdio`), but VS Code extensions live inside an already started Extension Host.
3. Giving you `manifest` helpers to automatically expose your existing `package.json` Copilot tool and skill declarations as MCP tools and resources.
4. Automatically registering the server configuration with Cursor using its undocumented `vscode.cursor.mcp.registerServer` API. (More zero-config integrations for other VS Code-hosted harnesses are planned).

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

You can build responses manually, or use the optional `vscode-mcp.manifest` and `vscode-mcp.responses` helpers to automatically parse `package.json` for tool/resource declarations and format standard responses.

For example, given this `package.json` declaration:

```json
{
  "contributes": {
    "languageModelTools": [
      {
        "name": "hello_world",
        "modelDescription": "Say hello to someone",
        "inputSchema": {
          "type": "object",
          "properties": {
            "name": { "type": "string" }
          },
          "required": ["name"]
        }
      }
    ]
  }
}
```

```clojure
(require '[vscode-mcp.server :as mcp-server])
(require '[vscode-mcp.manifest :as manifest])
(require '[vscode-mcp.responses :as responses])

(defn handle-mcp-request [{:keys [method params id] :as request} ^js context]
  (case method
    "initialize"
    (responses/clj-response id {:capabilities {:tools {} :resources {}}
                                :serverInfo {:name "my-server" :version "1.0.0"}})

    "tools/list"
    (responses/clj-response id {:tools (manifest/get-tools context)})

    "resources/list"
    (let [skills (manifest/get-resources context)]
      (responses/clj-response id {:resources skills}))

    "resources/read"
    (if-let [resource (manifest/read-resource context (:uri params))]
      (responses/clj-response id {:contents [resource]})
      (responses/error-response id -32602 "Resource not found"))

    "tools/call"
    (let [tool-name (:name params)
          args (:arguments params)]
      (case tool-name
        "hello_world"
        (responses/clj-response id (str "Hello, " (:name args "World") "!"))

        (responses/error-response id -32601 (str "Unknown tool: " tool-name))))

    (responses/error-response id -32601 "Method not found")))
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

## Too Opinionated for You?

This library is purpose-built to **minimize boilerplate for Copilot-native extensions** that also want to cater to other AI harnesses (like Cursor) via MCP. If you are not building an extension that provides Copilot `languageModelTools` and `chatSkills`, this library is likely not for you.

`vscode-mcp` is deeply environment-suited. It is used by [Calva Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver), and [Joyride](https://github.com/BetterThanTomorrow/joyride), and may or may not work for your use case.