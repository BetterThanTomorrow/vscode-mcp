# vscode-mcp

A [ClojureScript](https://clojurescript.org) library for exposing an MCP (Model Context Protocol) server from within a VS Code Extension Host.

The library is meant to be used for VS Code extensions that want to provide AI tools and resources both via Copilot and via MCP, with zero config for Cursor. It manages the TCP socket lifecycle and provides zero-config auto-registration for Cursor.

The libray works by:

1. Spawning a local TCP socket server inside the Extension Host.
2. Providing a pure Node.js `stdio` wrapper script that clients run. The wrapper relays streams to the TCP socket. This is because MCP clients expect to spawn an executable and communicate over standard input/output streams (`stdio`), but VS Code extensions live inside an already started Extension Host.
3. Giving you `manifest` helpers to automatically expose your existing `package.json` Copilot tool and skill declarations as MCP tools and resources.
4. Automatically registering the server configuration with Cursor using its [`vscode.cursor.mcp.registerServer`](https://cursor.com/docs/extension-api) API. (More zero-config integrations for other VS Code-hosted harnesses TBD).

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
    (responses/success-response id {:protocolVersion "2024-11-05"
                                    :capabilities {:tools {} :resources {}}
                                    :serverInfo {:name "my-server" :version "1.0.0"}})

    "tools/list"
    (responses/success-response id {:tools (manifest/get-tools context)})

    "resources/list"
    (let [skills (manifest/get-resources context)]
      (responses/success-response id {:resources skills}))

    "resources/read"
    (if-let [resource (manifest/read-resource context (:uri params))]
      (responses/success-response id {:contents [resource]})
      (responses/error-response id -32602 "Resource not found"))

    "tools/call"
    (let [tool-name (:name params)
          args (:arguments params)]
      (case tool-name
        "hello_world"
        (responses/success-response id {:content [{:type "text" :text (str "Hello, " (:name args "World") "!")}]})

        (responses/error-response id -32601 (str "Unknown tool: " tool-name))))

    (when id
      (responses/error-response id -32601 "Method not found"))))
```

### 4. Wire Up Lifecycle: Start, Stop, Cursor Registration

`vscode-mcp.lifecycle` is the recommended way to drive the server — it owns starting/stopping the socket, deciding when to auto-register with Cursor (with dedupe, so it won't register twice), and showing the manual-start dialog and copy-command clipboard UX (from `vscode-mcp.manual-setup`) when the user runs a manual "start" command. You get all of that by building one config map and holding one piece of state.

`vscode-mcp.lifecycle` is **stateless** — it holds no atom of its own. Every call takes the current lifecycle state as an explicit argument and resolves to the *next* state. You own that state (an atom, your app-db, whatever) and just pass whatever comes back into the next call, verbatim:

```clojure
(require '[vscode-mcp.lifecycle :as lifecycle])
(require '["path" :as path])

(defonce !lifecycle-state (atom (lifecycle/init-state)))
(defonce !lifecycle-config (atom nil))

(defn- build-lifecycle-config [^js context]
  (lifecycle/create-config
   {:vscode/extension-context context

    ;; Identifies your server to Cursor; script-relative-path is where your
    ;; stdio wrapper build (step 2) lands inside the extension's install dir.
    :cursor/server-name "my-extension"
    :cursor/script-relative-path "dist/mcp-server.js"

    ;; Settings you'd normally read from package.json contributes.configuration
    :mcp/auto-start? false
    :mcp/auto-register? true
    :server/host "127.0.0.1"

    :mcp/on-request handle-mcp-request

    ;; Strategy fns: your extension's own conventions for where things live.
    ;; Each receives {:lifecycle/cursor-mode? <bool>} as a second argument.
    :lifecycle/port-file-uri+ (fn [^js ctx _opts]
                                (vscode/Uri.joinPath (.-extensionUri ctx) "mcp-port"))
    :lifecycle/request-port (fn [_ctx _opts] 0) ;; 0 = OS-assigned free port
    :lifecycle/wrapper-path (fn [^js ctx _server-info]
                              (path/join (.-extensionPath ctx) "dist" "mcp-server.js"))}))

;; Config is static for the life of the extension host; build it once.
(defn- lifecycle-config! [^js context]
  (or @!lifecycle-config
      (reset! !lifecycle-config (build-lifecycle-config context))))

(defn activate [^js context]
  (-> (lifecycle/maybe-start!+ (lifecycle-config! context) @!lifecycle-state)
      (.then (fn [state] (reset! !lifecycle-state state)))))

(defn deactivate []
  ;; Silent by default: no "MCP server stopped" message.
  (-> (lifecycle/stop!+ (lifecycle-config! nil) @!lifecycle-state)
      (.then (fn [state] (reset! !lifecycle-state state)))))
```

`maybe-start!+` only starts the server when policy allows it (explicit `:mcp/auto-start?`, or Cursor auto-register with the Cursor MCP API available) — call it unconditionally from `activate`. For manual "Start MCP Server" / "Stop MCP Server" commands, call `start!+` / `stop!+` instead; unlike `maybe-start!+`, `start!+` always starts (unless already running) and shows the manual-start dialog with copy-to-clipboard buttons:

```clojure
(defn start-command! [^js context]
  (-> (lifecycle/start!+ (lifecycle-config! context) @!lifecycle-state)
      (.then (fn [state] (reset! !lifecycle-state state)))))

(defn stop-command! [^js context]
  (-> (lifecycle/stop!+ (lifecycle-config! context) @!lifecycle-state {:lifecycle/silent? false})
      (.then (fn [state] (reset! !lifecycle-state state)))))

(defn server-running? []
  (lifecycle/running? @!lifecycle-state))
```

`vscode-mcp.lifecycle` and `vscode-mcp.manual-setup.dialog` are the only namespaces that touch the live `vscode` module or open real sockets/dialogs (along with `vscode-mcp.server` and `vscode-mcp.cursor`, which they wrap). Everything else — `stdio-config`, `manifest`, `responses`, `policy`, and the pure halves `vscode-mcp.manual-setup` and `vscode-mcp.lifecycle.pure` — is plain data-in/data-out ClojureScript, unit-tested without ever requiring `"vscode"`.

If you'd rather orchestrate start/stop yourself, the lower-level building blocks `vscode-mcp.server/start-server!+` / `stop-server!+` and `vscode-mcp.cursor/register-and-reload-mcp-client!+` / `unregister-mcp-server!+` are still there and used internally by `vscode-mcp.lifecycle` — but most consumers should just use the lifecycle module above.

## Too Opinionated for You?

This library is purpose-built to **minimize boilerplate for Copilot-native extensions** that also want to cater to other AI harnesses (like Cursor) via MCP. If you are not building an extension that provides Copilot `languageModelTools` and `chatSkills`, this library is likely not for you.

`vscode-mcp` is deeply environment-suited. It is used by [Calva Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver), and [Joyride](https://github.com/BetterThanTomorrow/joyride), and may or may not work for your use case.

## Limitations & Shortcuts

To keep the library dependency-free and lightweight, it takes a few deliberate shortcuts:

1. **Naive YAML Frontmatter Parsing**: `vscode-mcp.manifest` parses Markdown frontmatter using a simple regex-based line parser rather than a full YAML parser. It correctly extracts top-level key/value pairs (including multi-line strings), but does not support advanced YAML features like lists, nested objects, or anchors.
2. **Strict JSON Schema Extraction**: When extracting tools from `package.json`'s `languageModelTools`, `vscode-mcp.manifest` filters the `inputSchema` to only include the `:type`, `:properties`, and `:required` keys at the root level. Advanced root-level JSON Schema features (like `additionalProperties` or `anyOf`) are silently dropped.
3. **Literal `when` Clause Matching**: VS Code `when` clauses are evaluated using a strict equality lookup against keys in the provided `settings` map. It does not parse or evaluate context expressions (e.g., `config.calva.something == true` or logical operators like `&&` / `||`); the entire clause string is treated as a literal key.