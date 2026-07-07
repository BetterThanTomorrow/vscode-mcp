# vscode-mcp

A [ClojureScript](https://clojurescript.org) library for exposing an MCP (Model Context Protocol) server from within a VS Code Extension Host.

The library is meant to be used for VS Code extensions that want to provide AI tools and resources both via Copilot and via MCP, with zero config for Cursor. It manages the TCP socket lifecycle and provides zero-config auto-registration for Cursor.

The library works by:

1. Spawning a local TCP socket server inside the Extension Host.
2. Providing a pure Node.js `stdio` wrapper script that clients run. The wrapper relays streams to the TCP socket. This is because MCP clients expect to spawn an executable and communicate over standard input/output streams (`stdio`), but VS Code extensions live inside an already started Extension Host.
3. Giving you `manifest` helpers to automatically expose your existing `package.json` Copilot tool and skill declarations as MCP tools and resources.
4. Automatically registering the server configuration with Cursor using its [`vscode.cursor.mcp.registerServer`](https://cursor.com/docs/extension-api) API. (More zero-config integrations for other VS Code-hosted harnesses TBD).

## Usage

### 1. Add the Dependency

Add `vscode-mcp` to your project's `deps.edn`:

```edn
io.github.betterthantomorrow/vscode-mcp {:git/url "https://github.com/BetterThanTomorrow/vscode-mcp.git"
                                         :git/sha "f7dfcc81158f67ac8ef76e55e992daaea14d0336"}
```

Update `:git/sha` to pin a specific commit (see [releases](https://github.com/BetterThanTomorrow/vscode-mcp/releases) or `git rev-parse HEAD` in a checkout).

For local development alongside your extension, use `:local/root` instead:

```edn
io.github.betterthantomorrow/vscode-mcp {:local/root "../vscode-mcp"}
```

### 2. Configure the Stdio Wrapper Build

Consumers must bundle the provided `stdio` wrapper script so the MCP client has a physical `.js` file to execute.

Add a build target to your `shadow-cljs.edn` that compiles the library's generic wrapper namespace into your distribution directory:

```edn
:stdio-wrapper {:target :node-script
                :main vscode-mcp.stdio.wrapper/main
                :output-to "dist/mcp-server.js"}
```

The wrapper is invoked as:

```bash
node dist/mcp-server.js <port-or-port-file-path> [host]
```

### 3. Implement the Request Handler

The server is stateless and protocol-agnostic. You must provide an `:mcp/on-request` callback that receives parsed JSON-RPC requests (e.g., `initialize`, `tools/list`, `tools/call`) and returns the JSON-RPC response (or a Promise resolving to it).

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
(require '[vscode-mcp.manifest :as manifest]
         '[vscode-mcp.responses :as responses])

(defn handle-mcp-request [{:keys [method params id] :as request} ^js context]
  (case method
    "initialize"
    (responses/success-response id
     (manifest/build-initialize-result context))

    "tools/list"
    (responses/success-response id {:tools (manifest/get-tools context)})

    "resources/list"
    (responses/success-response id {:resources (manifest/get-resources context)})

    "resources/read"
    (if-let [resource (manifest/read-resource context (:uri params))]
      (responses/success-response id {:contents [resource]})
      (responses/error-response id -32602 "Resource not found"))

    "tools/call"
    (let [tool-name (:name params)
          args (:arguments params)]
      (case tool-name
        "hello_world"
        (responses/success-response id {:content [{:type "text"
                                                   :text (str "Hello, " (:name args "World") "!")}]})

        (responses/error-response id -32601 (str "Unknown tool: " tool-name))))

    (when id
      (responses/error-response id -32601 "Method not found"))))
```

### 4. Wire Up Lifecycle: Start, Stop, Cursor Registration

`vscode-mcp.core` is where you drive the server — it owns starting/stopping the socket, deciding when to auto-register with Cursor, and showing the manual-start dialog and copy-command clipboard UX when the user runs a manual "start" command. You get all of that by building one config map and holding one piece of state.

`vscode-mcp.core` functions take the current lifecycle state as an argument and resolve to the *next* state. You own that state and thread whatever comes back into the next call, verbatim:

```clojure
(require '[vscode-mcp.core :as lifecycle]
         '["path" :as path]
         '["vscode" :as vscode])

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
           :manual-setup/extension-name "My Extension"
           :mcp/on-request handle-mcp-request
           :lifecycle/port-file-uri+ (fn [^js ctx _opts]
                                       (vscode/Uri.joinPath (.-extensionUri ctx) "mcp-port"))
           :lifecycle/request-port (fn [_ctx _opts]
                                     (:server/request-port (read-mcp-settings)))
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

`maybe-start!+` only starts the server when policy allows it (explicit `:mcp/auto-start?`, or Cursor auto-register with the Cursor MCP API available) — call it unconditionally from `activate`. For manual "Start MCP Server" / "Stop MCP Server" commands, call `start!+` / `stop!+` instead; unlike `maybe-start!+`, `start!+` always starts (unless already running) and shows the manual-start dialog with a copy-to-clipboard button when `silent?` is false.

```clojure
(defn start-command! [^js context]
  (-> (lifecycle/start!+ (build-lifecycle-config context) @!lifecycle-state false)
      (.then (fn [state] (reset! !lifecycle-state state))))

(defn stop-command! [^js context]
  (-> (lifecycle/stop!+ (build-lifecycle-config context) @!lifecycle-state
                        {:lifecycle/silent? false})
      (.then (fn [state] (reset! !lifecycle-state state))))

(defn register-with-cursor-command! [^js context]
  (-> (lifecycle/register-with-cursor!+ (build-lifecycle-config context) @!lifecycle-state)
      (.then (fn [result]
               (when (:ok result)
                 (reset! !lifecycle-state (:state result)))
               result)))

(defn server-running? []
  (lifecycle/running? @!lifecycle-state))
```

#### Lifecycle functions

| Function | Returns | Purpose |
|----------|---------|---------|
| `init-state` | State map | Fresh lifecycle state |
| `create-config` | Config map | Merge your opts with library defaults |
| `running?` | Boolean | Whether the socket server is up |
| `server-info` | Map or `nil` | Port, host, port-file URI, etc. |
| `cursor-registered?` | Boolean | Whether Cursor registration succeeded |
| `maybe-start!+` | Promise → state | Start when auto-start policy allows |
| `start!+` | Promise → state | Always start (unless already running) |
| `stop!+` | Promise → state | Stop socket and unregister from Cursor |
| `register-with-cursor!+` | Promise → `{:ok … :state … :reason …}` | Start if needed, then register with Cursor |

`maybe-start!+` and `start!+` take a boolean `silent?` third argument. When `silent?` is false, `start!+` shows the manual-start information dialog (with a "Copy command" button that writes the port-file command to the clipboard).

`stop!+` takes a stop-options map (or a legacy boolean treated as `:lifecycle/silent?`):

| Key | Default | Meaning |
|-----|---------|---------|
| `:lifecycle/silent?` | `true` | When false, shows the "MCP server stopped" message |

Stop always unregisters from Cursor (best-effort), stops the socket, and returns fresh init-state. If the server had been registered with Cursor, `:lifecycle/generation` is incremented so the next register uses a new generation-suffixed server name.

#### `register-with-cursor!+`

Starts the socket server if it is not already running (silently, without auto-register), then registers with Cursor. Resolves to:

```clojure
{:ok true|false
 :state <next-state>
 :reason :cursor-api-unavailable|:registration-failed}  ; when :ok is false
```

Use this for a manual "Register with Cursor" command. When `:mcp/auto-register?` is true, registration also happens automatically during `start!+` / `maybe-start!+`.

#### Config map keys

`create-config` supplies these defaults:

| Key | Default |
|-----|---------|
| `:mcp/auto-start?` | `false` |
| `:mcp/auto-register?` | `true` |
| `:server/host` | `"127.0.0.1"` |

You typically read host, port, and auto-start/register flags from your extension's VS Code settings and pass them into `create-config`. The library does not read VS Code settings itself. Do not cache the lifecycle config for the whole activation — rebuild it on each `maybe-start!+`, `start!+`, and `stop!+` call so transport settings take effect on the **next server start**.

**Required:**

| Key | Role |
|-----|------|
| `:vscode/extension-context` | VS Code extension context |
| `:cursor/server-name` | Base name for Cursor registration (gets instance slug + generation suffix) |
| `:cursor/script-relative-path` | Relative path to the bundled stdio wrapper JS |
| `:mcp/on-request` | `(fn [request] → response \| Promise)` |
| `:lifecycle/port-file-uri+` | `(fn [ctx strategy-opts] → Uri)` — where to write the port file |
| `:lifecycle/request-port` | `(fn [ctx strategy-opts] → port-number)` |
| `:lifecycle/wrapper-path` | `(fn [ctx server-info] → absolute-path-to-wrapper.js)` |

**Optional callbacks:**

| Key | When called |
|-----|-------------|
| `:mcp/on-log` | `(fn [level & args])` — levels `:debug` … `:error` |
| `:mcp/on-error` | Start flow failure |
| `:lifecycle/on-starting-changed` | `(fn [starting?])` |
| `:lifecycle/on-stopping-changed` | `(fn [stopping?])` |
| `:lifecycle/on-running-changed` | `(fn [running? server-info])` |
| `:lifecycle/on-cursor-registered` | `(fn [result])` |
| `:lifecycle/on-cursor-registration-failed` | `(fn [failure])` |
| `:manual-setup/extension-name` | Shown in manual-start/stop messages |
| `:manual-setup/message-suffix` | Extra text appended to the manual-start dialog |

**Strategy opts** passed to `:lifecycle/port-file-uri+` and `:lifecycle/request-port`:

```clojure
{:lifecycle/cursor-mode? boolean    ; auto-register enabled and Cursor API available
 :lifecycle/instance-slug string}   ; per-window slug (ws-{hash} or win-{hash})
```

Consumers often vary port-file location and requested port based on `:lifecycle/cursor-mode?` — for example, writing the port file to a temp directory when Cursor auto-register is active.

#### Lifecycle state

State is a plain map you store (typically in an atom). Keys managed by the library:

| Key | Meaning |
|-----|---------|
| `:lifecycle/server-info` | Running server details, or `nil` when stopped |
| `:lifecycle/registered-name` | Current generation-suffixed Cursor registration name |
| `:lifecycle/generation` | Registration generation counter (survives stop) |

#### Socket readiness

Before Cursor registration on start, the library probes TCP connect to the assigned port (5 s timeout, 100 ms interval). A timeout logs a warning via `:mcp/on-log` and registration proceeds anyway.

## Public API

### `vscode-mcp.core`

Lifecycle orchestration — the primary entry point. See section 4 above.

### `vscode-mcp.manifest`

Bridge `package.json` Copilot declarations to MCP:

| Function | Purpose |
|----------|---------|
| `get-tools` | MCP `tools/list` from `contributes.languageModelTools` |
| `get-resources` | MCP `resources/list` from `contributes.chatSkills` |
| `read-resource` | MCP `resources/read` — reads skill file content |
| `tool-call-allowed?` | Returns `:allowed`, `:disabled`, or `:unknown` for a tool name |
| `build-server-instructions` | Compose `initialize` instructions text |
| `build-initialize-result` | Full MCP `initialize` result map |

Optional `{:settings {when-clause-string boolean}}` filters tools/skills by VS Code `when` clauses (literal key lookup — see Limitations).

### `vscode-mcp.responses`

JSON-RPC response helpers:

| Function | Purpose |
|----------|---------|
| `success-response` | Generic success (`initialize`, `tools/list`, …) |
| `error-response` | JSON-RPC error |
| `text-response` | Tool result — JSON-stringified text content |
| `clj-response` | Tool result — `clj->js` then JSON-stringified |
| `content-response` | Tool result — pre-built content array |

### `vscode-mcp.server`

| Function | Purpose |
|----------|---------|
| `send-notification-params` | Push server-initiated JSON-RPC notifications to connected clients |

`start-server!+` / `stop-server!+` exist but are internal — always drive start/stop through `vscode-mcp.core`.

### `vscode-mcp.manual-setup`

| Function | Purpose |
|----------|---------|
| `copy-command-strings` | Returns `{:manual-setup/port … :manual-setup/port-file …}` shell commands for manual MCP client setup |

Use this to build your own manual-setup UI without the built-in dialog.

### `vscode-mcp.cursor`

Low-level Cursor helpers (also used internally by `core`):

| Function | Purpose |
|----------|---------|
| `cursor-mcp-available?` | Whether `vscode.cursor.mcp.registerServer` exists |
| `current-instance-slug` | Per-window slug shared by port files and server names |
| `register-mcp-server!+` | Register + reload client (used by `core`) |
| `unregister-by-name!+` | Unregister a generation-suffixed server name |
| `cleanup-tracked-registrations!+` | Sweep stale registrations from workspaceState |

### `vscode-mcp.policy`

Pure decision helpers useful for UI and tests:

| Function | When true |
|----------|-----------|
| `should-auto-start?` | `:mcp/auto-start?` OR (auto-register + Cursor available) |
| `should-register-with-cursor?` | auto-register + Cursor available + port file present |
| `should-register-on-start?` | above, and not `:lifecycle/skip-register?` |

## Too Opinionated for You?

This library is purpose-built to **minimize boilerplate for Copilot-native extensions** that also want to cater to other AI harnesses (like Cursor) via MCP. If you are not building an extension that provides Copilot `languageModelTools` and `chatSkills`, this library is likely not for you.

`vscode-mcp` is deeply environment-suited. It is used by [Calva Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver) and [Joyride](https://github.com/BetterThanTomorrow/joyride), and may or may not work for your use case.

## Stdio Wrapper Connect-Retry

The bundled `vscode-mcp.stdio.wrapper` script waits for the extension host to start the TCP server before giving up:

- **Connect-retry:** up to 60 s budget, 500 ms fixed interval between attempts.
- **Port file re-read:** each attempt re-resolves the port (numeric arg as-is; port-file arg re-read from disk).
- **Stdin buffered during wait:** listeners attach at startup; queued lines flush to the socket on connect (partial-line remainder carried into live forwarding).
- **Exit-on-close after first connection:** once connected, socket close or error exits (Cursor respawns the wrapper).
- **Exit if stdin closes during wait:** if the client gives up before the server is up, the wrapper exits promptly instead of orphan-retrying.

## Cursor Registration

When auto-register is enabled and the Cursor MCP API is available, registration happens automatically during start. Server names are generation-suffixed: `<base>-<instance-slug>-g<generation>` (e.g. `my-extension-ws-abc123-g0`). Cursor stalls when the same name is re-registered in one session after unregister, so the library always includes the generation in the name.

On each register within a session:

1. If a previous registration exists, it is unregistered first and generation increments.
2. `registerServer` is called with the new generation-suffixed name.
3. On success, `mcp.reloadClient` is always executed.
4. Stale registration names are tracked in `workspaceState` under `vscode-mcp.cursor/registered-names` and swept on cleanup.

When `:mcp/auto-register?` is false but the Cursor API is available, `maybe-start!+` sweeps stale tracked registrations on activate (without starting the server).

### In-session stop → start

Stop unregisters from Cursor (best-effort), stops the socket, and returns fresh init-state with an incremented `:lifecycle/generation` when the server had been registered. The next start waits for socket readiness, registers under a new generation-suffixed name, and reloads the MCP client. Extension deactivate should use silent stop: `{:lifecycle/silent? true}`.

## Limitations & Shortcuts

To keep the library dependency-free and lightweight, it takes a few deliberate shortcuts:

1. **Naive YAML Frontmatter Parsing**: `vscode-mcp.manifest` parses Markdown frontmatter using a simple regex-based line parser rather than a full YAML parser. It correctly extracts top-level key/value pairs (including multi-line strings), but does not support advanced YAML features like lists, nested objects, or anchors.
2. **Strict JSON Schema Extraction**: When extracting tools from `package.json`'s `languageModelTools`, `vscode-mcp.manifest` filters the `inputSchema` to only include the `:type`, `:properties`, and `:required` keys at the root level. Advanced root-level JSON Schema features (like `additionalProperties` or `anyOf`) are silently dropped.
3. **Literal `when` Clause Matching**: VS Code `when` clauses are evaluated using a strict equality lookup against keys in the provided `settings` map. It does not parse or evaluate context expressions (e.g., `config.calva.something == true` or logical operators like `&&` / `||`); the entire clause string is treated as a literal key.
