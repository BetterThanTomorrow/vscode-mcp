# vscode-mcp

A [ClojureScript](https://clojurescript.org) library for VS Code extensions that already declare Copilot **`languageModelTools`** and **`chatSkills`** in `package.json`, and want the same tools and resources available over MCP — with zero-config Cursor registration.

The library:

1. Runs a TCP socket MCP server inside the Extension Host.
2. Bundles a Node.js `stdio` wrapper script that MCP clients spawn; the wrapper relays stdin/stdout to the socket.
3. Reads your existing Copilot manifest and exposes it as MCP tools and resources.
4. Auto-registers with Cursor via [`vscode.cursor.mcp.registerServer`](https://cursor.com/docs/extension-api).

If your extension does not declare Copilot tools and skills, this library is not for you.

## Usage

### 1. Add the Dependency

```edn
io.github.betterthantomorrow/vscode-mcp {:git/url "https://github.com/BetterThanTomorrow/vscode-mcp.git"
                                         :git/sha "f7dfcc81158f67ac8ef76e55e992daaea14d0336"}
```

Update `:git/sha` to pin a specific commit (see [releases](https://github.com/BetterThanTomorrow/vscode-mcp/releases) or `git rev-parse HEAD` in a checkout).

### 2. Configure the Stdio Wrapper Build

Add a build target to your `shadow-cljs.edn`:

```edn
:stdio-wrapper {:target :node-script
                :main vscode-mcp.stdio.wrapper/main
                :output-to "dist/mcp-server.js"}
```

### 3. Wire the Manifest to MCP

Declare tools and skills in `package.json` under `contributes.languageModelTools` and `contributes.chatSkills`. Implement `:mcp/on-request` using `vscode-mcp.manifest` for discovery and `vscode-mcp.responses` for JSON-RPC formatting. You implement `tools/call` yourself — dispatch to the same code your Copilot tools use.

```clojure
(require '[vscode-mcp.manifest :as manifest]
         '[vscode-mcp.responses :as responses])

(defn- settings-map []
  ;; Map VS Code `when` clause strings to booleans for manifest filtering.
  {"config.my-extension.someSetting" true})

(defn handle-mcp-request [{:keys [method params id] :as request} ^js context]
  (let [settings (settings-map)]
    (case method
      "initialize"
      (responses/success-response id
       (manifest/build-initialize-result context {:settings settings}))

      "tools/list"
      (responses/success-response id
       {:tools (manifest/get-tools context {:settings settings})})

      "resources/list"
      (responses/success-response id
       {:resources (manifest/get-resources context {:settings settings})})

      "resources/read"
      (if-let [resource (manifest/read-resource context (:uri params) {:settings settings})]
        (responses/success-response id {:contents [(dissoc resource :skill-path)]})
        (responses/error-response id -32602 "Resource not found"))

      "tools/call"
      (let [tool-name (:name params)
            args (:arguments params)
            allowed (manifest/tool-call-allowed? context tool-name {:settings settings})]
        (cond
          (= :disabled allowed)
          (responses/error-response id -32601 "Unknown tool")

          (= :unknown allowed)
          (responses/error-response id -32601 (str "Unknown tool: " tool-name))

          :else
          ;; Dispatch to your tool implementation (same as Copilot invoke-tool).
          (responses/success-response id {:content [{:type "text" :text "…"}]})))

      (when id
        (responses/error-response id -32601 "Method not found")))))
```

Pass a `:settings` map when tools or skills use `when` clauses in `package.json`. Keys are the full clause strings; values are booleans (see Limitations).

### 4. Wire Up Lifecycle

`vscode-mcp.core` drives start/stop, Cursor registration, and the manual-start dialog. Build one config map, hold one lifecycle state atom, and rebuild the config on every lifecycle call so settings changes apply on the next start.

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
           :lifecycle/port-file-uri+ (fn [^js ctx {:lifecycle/keys [cursor-mode? instance-slug]}]
                                       (if cursor-mode?
                                         ;; Cursor auto-register: stable port file outside workspace
                                         (vscode/Uri.file (str "/tmp/my-extension-mcp/" instance-slug "/port"))
                                         (vscode/Uri.joinPath (.-extensionUri ctx) "mcp-port")))
           :lifecycle/request-port (fn [_ctx {:lifecycle/keys [cursor-mode?]}]
                                     (if cursor-mode? 0 (:server/request-port (read-mcp-settings))))
           :lifecycle/wrapper-path (fn [^js ctx _server-info]
                                     (path/join (.-extensionPath ctx) "dist" "mcp-server.js"))
           :lifecycle/on-running-changed (fn [running? _info]
                                           ;; Sync VS Code when-contexts, status bar, etc.
                                           )})))

(defn activate [^js context]
  (-> (lifecycle/maybe-start!+ (build-lifecycle-config context) @!lifecycle-state true)
      (.then #(reset! !lifecycle-state %))))

(defn deactivate []
  (-> (lifecycle/stop!+ (build-lifecycle-config nil) @!lifecycle-state {:lifecycle/silent? true})
      (.then #(reset! !lifecycle-state %))))

(defn start-command! [^js context]
  (-> (lifecycle/start!+ (build-lifecycle-config context) @!lifecycle-state false)
      (.then #(reset! !lifecycle-state %))))

(defn stop-command! [^js context]
  (-> (lifecycle/stop!+ (build-lifecycle-config context) @!lifecycle-state
                        {:lifecycle/silent? false})
      (.then #(reset! !lifecycle-state %))))

(defn register-with-cursor-command! [^js context]
  (-> (lifecycle/register-with-cursor!+ (build-lifecycle-config context) @!lifecycle-state)
      (.then (fn [result]
               (when (:ok result) (reset! !lifecycle-state (:state result)))
               result))))
```

Call `maybe-start!+` unconditionally from `activate`. It starts when `:mcp/auto-start?` is true, or when Cursor auto-register is enabled and the Cursor MCP API is available. Use `start!+` for manual start commands — it always starts (unless already running) and shows the manual-start dialog with a copy-to-clipboard button.

#### Lifecycle API

| Function | Purpose |
|----------|---------|
| `init-state` | Fresh lifecycle state |
| `create-config` | Merge your opts with defaults (`:server/host` defaults to `"127.0.0.1"`) |
| `running?` / `server-info` / `cursor-registered?` | Query current state |
| `maybe-start!+` | Start when policy allows |
| `start!+` | Always start |
| `stop!+` | Stop socket and unregister from Cursor |
| `register-with-cursor!+` | Start if needed, then register with Cursor |

Stop unregisters from Cursor (best-effort), stops the socket, and returns fresh init-state. If the server had been registered, `:lifecycle/generation` increments so the next register uses a new generation-suffixed server name.

Before Cursor registration, the library probes TCP connect to the assigned port (5 s timeout). A timeout logs a warning and registration proceeds anyway.

## Reference Implementations

Used by [Calva Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver) and [Joyride](https://github.com/BetterThanTomorrow/joyride).

## Stdio Wrapper Connect-Retry

The bundled `vscode-mcp.stdio.wrapper` script waits for the extension host to start the TCP server before giving up:

- **Connect-retry:** up to 60 s budget, 500 ms fixed interval between attempts.
- **Port file re-read:** each attempt re-resolves the port (numeric arg as-is; port-file arg re-read from disk).
- **Stdin buffered during wait:** queued lines flush to the socket on connect.
- **Exit-on-close after first connection:** socket close or error exits (Cursor respawns the wrapper).
- **Exit if stdin closes during wait:** the wrapper exits promptly instead of orphan-retrying.

## Cursor Registration

When auto-register is enabled and the Cursor MCP API is available, registration happens automatically during start. Server names are generation-suffixed: `<base>-<instance-slug>-g<generation>` (e.g. `my-extension-ws-abc123-g0`).

On each register within a session:

1. If a previous registration exists, it is unregistered first and generation increments.
2. `registerServer` is called with the new generation-suffixed name.
3. On success, `mcp.reloadClient` is always executed.
4. Stale registration names are tracked in `workspaceState` under `vscode-mcp.cursor/registered-names` and swept on cleanup.

When `:mcp/auto-register?` is false but the Cursor API is available, `maybe-start!+` sweeps stale tracked registrations on activate (without starting the server).

### In-session stop → start

Stop unregisters from Cursor, stops the socket, and returns fresh init-state with an incremented `:lifecycle/generation` when the server had been registered. The next start registers under a new generation-suffixed name and reloads the MCP client. Extension deactivate should use `{:lifecycle/silent? true}`.

## Limitations

1. **Naive YAML frontmatter parsing** — `vscode-mcp.manifest` uses a regex line parser for skill frontmatter. No lists, nested objects, or YAML anchors.
2. **Strict JSON Schema extraction** — tool `inputSchema` is filtered to `:type`, `:properties`, and `:required` only.
3. **Literal `when` clause matching** — the `:settings` map keys must match `when` clause strings exactly. No expression evaluation (`&&`, `||`, comparisons).
