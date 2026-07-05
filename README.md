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

`vscode-mcp.core` from where you drive the server — it owns starting/stopping the socket, deciding when to auto-register with Cursor, and showing the manual-start dialog and copy-command clipboard UX (from `vscode-mcp.manual-setup`) when the user runs a manual "start" command. You get all of that by building one config map and holding one piece of state.

`vscode-mcp.core` functions take the current lifecycle state as an argument and resolves to the *next* state. You own that state and thread whatever comes back into the next call, verbatim:

```clojure
(require '[vscode-mcp.core :as lifecycle])
(require '["path" :as path])
(require '["vscode" :as vscode])

(defonce !lifecycle-state (atom (lifecycle/init-state)))

(defn- read-mcp-settings []
  (let [cfg (vscode/workspace.getConfiguration "my-extension.mcp")]
    {:mcp/auto-start? (.get cfg "autoStartServer" false)
     :mcp/auto-register? (.get cfg "autoRegisterCursor" true)
     :server/host (.get cfg "host")
     :server/request-port (.get cfg "port" 0)}))

(defn- build-lifecycle-config [^js context]
  (lifecycle/create-config
   (merge (read-mcp-settings)
          {:vscode/extension-context context
           :cursor/server-name "my-extension"
           :cursor/script-relative-path "dist/mcp-server.js"
           :mcp/on-request handle-mcp-request
           :lifecycle/port-file-uri+ (fn [^js ctx _opts]
                                       (vscode/Uri.joinPath (.-extensionUri ctx) "mcp-port"))
           :lifecycle/request-port (fn [_ctx _opts] (:server/request-port (read-mcp-settings)))
           :lifecycle/wrapper-path (fn [^js ctx _server-info]
                                     (path/join (.-extensionPath ctx) "dist" "mcp-server.js"))})))

;; Rebuild config on each lifecycle call so Settings changes (host, port, …)
;; take effect on the next server start without reloading the window.
(defn activate [^js context]
  (-> (lifecycle/maybe-start!+ (build-lifecycle-config context) @!lifecycle-state true)
      (.then (fn [state] (reset! !lifecycle-state state)))))

(defn deactivate []
  (-> (lifecycle/stop!+ (build-lifecycle-config nil) @!lifecycle-state {:lifecycle/silent? true})
      (.then (fn [state] (reset! !lifecycle-state state)))))
```

`maybe-start!+` only starts the server when policy allows it (explicit `:mcp/auto-start?`, or Cursor auto-register with the Cursor MCP API available) — call it unconditionally from `activate`. For manual "Start MCP Server" / "Stop MCP Server" commands, call `start!+` / `stop!+` instead; unlike `maybe-start!+`, `start!+` always starts (unless already running) and shows the manual-start dialog with copy-to-clipboard buttons.

`maybe-start!+` / `start!+` / `stop!+` all take `silent?` as a required third argument — no default, so every call site states its intent:

```clojure
(defn start-command! [^js context]
  (-> (lifecycle/start!+ (build-lifecycle-config context) @!lifecycle-state false)
      (.then (fn [state] (reset! !lifecycle-state state)))))

(defn stop-command! [^js context]
  (-> (lifecycle/stop!+ (build-lifecycle-config context) @!lifecycle-state
                        {:lifecycle/silent? false})
      (.then (fn [state] (reset! !lifecycle-state state)))))

(defn register-with-cursor-command! [^js context]
  (-> (lifecycle/register-or-start-with-cursor!+ (build-lifecycle-config context) @!lifecycle-state)
      (.then (fn [result]
               (when (:ok result)
                 (reset! !lifecycle-state (:state result)))
               result))))

(defn server-running? []
  (lifecycle/running? @!lifecycle-state))
```

**Stop options map.** `stop!+` accepts a map (or legacy boolean `silent?`):

| Key | Default | Meaning |
|-----|---------|---------|
| `:lifecycle/silent?` | `true` | When false, shows the "MCP server stopped" message |

Stop always unregisters from Cursor (best-effort) and sets `:lifecycle/needs-cursor-reregister?` on the returned init-state so the next start forces client reload.

**Manual Cursor registration.** `register-with-cursor!+` requires a running server (warm repair). `register-or-start-with-cursor!+` implements Option C semantics: when `:mcp/auto-register?` is false it starts the server if needed then registers; when auto-register is on it is repair-only (server must already be running). Both resolve to `{:ok … :state … :reason …}`.

**Socket readiness.** Before Cursor registration on start, `start-flow!+` probes TCP connect to the assigned port (up to 5 s). A timeout logs a warning and registration proceeds anyway.

**Settings and transport options.** Declare setting names and defaults in your extension's `package.json`; read them in `build-lifecycle-config` via `workspace.getConfiguration`. `vscode-mcp` does not read VS Code settings or supply host/port defaults — consumers pass explicit values (including required `:server/host`). Do not cache the lifecycle config for the whole activation: rebuild it on each `maybe-start!+`, `start!+`, and `stop!+` call. Transport settings such as host and port then take effect on the **next server start**.

## Too Opinionated for You?

This library is purpose-built to **minimize boilerplate for Copilot-native extensions** that also want to cater to other AI harnesses (like Cursor) via MCP. If you are not building an extension that provides Copilot `languageModelTools` and `chatSkills`, this library is likely not for you.

`vscode-mcp` is deeply environment-suited. It is used by [Calva Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver), and [Joyride](https://github.com/BetterThanTomorrow/joyride), and may or may not work for your use case.

## Stdio Wrapper Connect-Retry

The bundled `vscode-mcp.stdio.wrapper` script waits for the extension host to start the TCP server before giving up:

- **Connect-retry:** up to 60 s budget, 500 ms fixed interval between attempts.
- **Port file re-read:** each attempt re-resolves the port (numeric arg as-is; port-file arg re-read from disk).
- **Stdin buffered during wait:** listeners attach at startup; queued lines flush to the socket on connect (partial-line remainder carried into live forwarding).
- **Exit-on-close after first connection:** unchanged — once connected, socket close or error exits as before (Cursor respawns the wrapper).
- **Exit if stdin closes during wait:** if Cursor gives up before the server is up, the wrapper exits promptly instead of orphan-retrying.

## Cursor Reload Policy

`register-and-reload-mcp-client!+` gates `mcp.reloadClient` so routine silent activations do not force-restart a healthy client:

- **Persistence:** last registered config stored in `workspaceState` under `vscode-mcp.cursor/last-registered-config:{server-name}` (workspace scope because the port-file path in `:args` is workspace-derived). After `unregisterServer`, a `vscode-mcp.cursor/pending-reload-after-unregister:{server-name}` flag is set so the next register triggers reload even when config is unchanged.
- **`:lifecycle/silent?`:** passed on `register-and-reload-mcp-client!+`; missing or `nil` ⇒ always reload (backward-safe default matching prior behavior).
- **Skipped reload result:** `{:ok true :skipped :unchanged-config}` when reload is skipped (`:ok true` is load-bearing for consumers that warn on failed reload).
- **`should-reload-client?` policy:** reload on manual start (`silent?` false/nil), when the registered config changed, after unregister+register (pending-reload flag), when `:cursor/needs-cursor-reregister?` is set after manual stop unregister, or when `:cursor/force-reload?` is passed; silent activation with unchanged config and no prior unregister skips reload.

## In-Session Stop→Start

Stop always unregisters from Cursor (best-effort), then stops the socket, and returns init-state with `:lifecycle/needs-cursor-reregister?` true. The next start waits for socket readiness, registers, and forces client reload via that flag. Extension deactivate uses silent stop-options `{:lifecycle/silent? true}`. See `mcp-stop-start-cursor-registration-plan.md` in joyride-dev-docs for the full investigation; window-reload stability is covered separately in `mcp-wrapper-retry-and-reload-policy-plan.md`.

## Limitations & Shortcuts

To keep the library dependency-free and lightweight, it takes a few deliberate shortcuts:

1. **Naive YAML Frontmatter Parsing**: `vscode-mcp.manifest` parses Markdown frontmatter using a simple regex-based line parser rather than a full YAML parser. It correctly extracts top-level key/value pairs (including multi-line strings), but does not support advanced YAML features like lists, nested objects, or anchors.
2. **Strict JSON Schema Extraction**: When extracting tools from `package.json`'s `languageModelTools`, `vscode-mcp.manifest` filters the `inputSchema` to only include the `:type`, `:properties`, and `:required` keys at the root level. Advanced root-level JSON Schema features (like `additionalProperties` or `anyOf`) are silently dropped.
3. **Literal `when` Clause Matching**: VS Code `when` clauses are evaluated using a strict equality lookup against keys in the provided `settings` map. It does not parse or evaluate context expressions (e.g., `config.calva.something == true` or logical operators like `&&` / `||`); the entire clause string is treated as a literal key.