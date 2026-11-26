# vscode-mcp

A [ClojureScript](https://clojurescript.org) library for VS Code extensions that already declare Copilot **`languageModelTools`** and **`chatSkills`** in `package.json`, and want the same tools and resources available over MCP: zero-config Cursor registration, optional ECA registration, and an optional window registry so external agents can discover and connect.

The library:

1. Runs a TCP socket MCP server inside the Extension Host.
2. Bundles a Node.js `stdio` wrapper that MCP clients spawn; the wrapper relays stdin/stdout to the socket.
3. Reads your existing Copilot manifest and exposes it as MCP tools and resources.
4. Auto-registers with Cursor via [`vscode.cursor.mcp.registerServer`](https://cursor.com/docs/extension-api).
5. Optionally keeps a **window registry** at `~/.config/vscode-mcp/registry`: one entry per editor window, with discovery and attach information. Point an agent at that directory and ask it to connect.
   * `bb list` discovers windows
   * `bb mcp` is the no-session attach path when the agent cannot hold MCP. Feature doc: [docs/registry.md](docs/registry.md).

If your extension does not declare Copilot tools and skills, this library is not for you.

Agent/dev contract (lifecycle API, skill URIs, co-development with consumers): see [AGENTS.md](AGENTS.md).

## Usage

### 1. Add the Dependency

```edn
io.github.betterthantomorrow/vscode-mcp {:git/url "https://github.com/BetterThanTomorrow/vscode-mcp.git"
                                         :git/sha "507f250a0786f0264c34c27537462c30e825416c"}
```

Update `:git/sha` to pin a specific commit (see [releases](https://github.com/BetterThanTomorrow/vscode-mcp/releases) or `git rev-parse HEAD` in a checkout).

Your build needs ClojureScript 1.12.145 or later. The library's source uses async functions (`^:async` and `await`), and it's compiled as part of your extension.

### 2. Configure the Stdio Wrapper Build

```edn
:stdio-wrapper {:target :node-script
                :main vscode-mcp.stdio.wrapper/main
                :output-to "dist/mcp-server.js"}
```

### 3. Wire the Manifest to MCP

Declare tools and skills in `package.json`. Implement `:mcp/on-request` with `tools/call` locally and delegate everything else to `vscode-mcp.requests/handle-manifest-request` (covers `initialize`, `tools/list`, `resources/*`, `ping`).

The manifest functions work on your manifest as data. `manifest/manifest-data` reads it from the extension context.

```clojure
(require '[vscode-mcp.manifest :as manifest]
         '[vscode-mcp.requests :as mcp-requests]
         '[vscode-mcp.responses :as responses])

(defn- settings-map []
  {"config.my-extension.someSetting" true})

(defn handle-mcp-request [^js context {:keys [method params id] :as request}]
  (let [manifest (manifest/manifest-data context)
        opts {:settings (settings-map)
              :initialize-opts {:settings (settings-map)}}]
    (case method
      "tools/call"
      (let [tool-name (:name params)
            args (:arguments params)
            allowed (manifest/tool-call-allowed? manifest tool-name {:settings (:settings opts)})]
        (cond
          (or (= :disabled allowed) (= :unknown allowed))
          (responses/error-response id -32601 "Unknown tool")

          :else
          ;; Dispatch to your Copilot invoke-tool implementation.
          (responses/success-response id {:content [{:type "text" :text "…"}]})))

      (mcp-requests/handle-manifest-request manifest request opts))))
```

The handler returns a response, or a promise of one. Any thenable will do, so promesa's promises work too. A handler that resolves to `nil` sends nothing.

Skills from `chatSkills` show up as MCP resources at `skill://{name}/SKILL.md`. Details and optional hooks (`:resource-templates+`, `:read-resource+`, `:initialize-merge`) are in [AGENTS.md](AGENTS.md).

### 4. Wire Up Lifecycle

`vscode-mcp.core` drives start/stop, Cursor registration, and the manual-start dialog. The library keeps the server. You build one config map, and rebuild it on every lifecycle call so settings changes apply on the next start. The library tells you about the server through `:mcp/on-server-changed`.

```clojure
(require '[vscode-mcp.core :as lifecycle]
         '["os" :as os]
         '["path" :as path]
         '["vscode" :as vscode])

(defn- read-mcp-settings []
  (let [cfg (vscode/workspace.getConfiguration "my-extension.mcp")]
    {:mcp/auto-start? (.get cfg "autoStartServer" false)
     :mcp/auto-register? (.get cfg "autoRegisterCursor" true)
     :mcp/auto-register-eca? (.get cfg "autoRegisterEca" true)
     :server/host (.get cfg "host")
     :server/request-port (.get cfg "port" 0)}))

(defn- server-changed!
  "Called with the server's state, when it changed."
  [{:server/keys [running?]}]
  ;; Sync VS Code when-contexts, status bar, etc.
  (vscode/commands.executeCommand "setContext" "my-extension.mcpServerRunning" (boolean running?)))

(defn- build-lifecycle-config [^js context]
  (lifecycle/create-config
   (merge (read-mcp-settings)
          {:vscode/extension-context context
           :server/name "my-extension"
           :wrapper/script-relative-path "dist/mcp-server.js"
           :manual-setup/extension-name "My Extension"
           :lifecycle/wrapper-install-dir (path/join (os/homedir) ".config" "my-extension")
           :mcp/on-request (partial handle-mcp-request context)
           :mcp/on-server-changed server-changed!})))

(defn activate [^js context]
  (lifecycle/maybe-start!+ (build-lifecycle-config context)))

(defn deactivate []
  (lifecycle/stop!+ (build-lifecycle-config nil) {:lifecycle/silent? true}))
```

Call `maybe-start!+` from `activate`. Use `start!+` / `stop!+` / `register-with-cursor!+` for commands. Each takes the config and returns a promise of the server's state. `:lifecycle/wrapper-install-dir` is required (symlink in DEBUG, copy in release).

The server's state is plain data. It's what `:mcp/on-server-changed` gets, what the lifecycle calls resolve to, and what `(lifecycle/server-state config)` returns:

```clojure
{:server/running? true
 :server/starting? false
 :server/stopping? false
 :server/host "127.0.0.1"
 :server/assigned-port 49311
 :server/instance-slug "ws-bxnohh"
 :server/port-file-path "/home/me/.config/vscode-mcp/port-files/my-extension-ws-bxnohh.port"
 :server/error nil                      ; the message of a start that failed
 :lifecycle/registered-name nil         ; the server's name in Cursor, when it is registered
 :lifecycle/registration-error nil}     ; {:reason … :message …} when a Cursor registration failed
```

Some things to know about how the server behaves:

* A start that fails resolves to a state with `:server/error`, and leaves no server behind (no open socket, no port file).
* A Cursor registration that fails leaves the server running, with `:lifecycle/registration-error`.
* In Cursor, a server that gets registered at start listens on a port the system picks, whatever `:server/request-port` says.
* A stop while the server is still starting does nothing. Neither does a second start.

### Optional window registry

Pass `:registry/enabled? true` to join the registry. Put what agents should discover about your window (for example REPL sessions) in `:registry/custom-data`, a map, and call `update-registry!+` with a config that has the new data when it changes. Wiring: [docs/registry.md](docs/registry.md).

### Optional ECA registration

Library default for `:mcp/auto-register-eca?` is **false**. Pass `true` from your setting if you want ECA `.eca/config.json` registration. Full Cursor/ECA behavior: [AGENTS.md](AGENTS.md).

## Reference Implementations

[Calva Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver) and [Joyride](https://github.com/BetterThanTomorrow/joyride).

## Limitations

1. **Naive YAML frontmatter parsing** — regex line parser for skill frontmatter; a value quoted as a whole is read without its quotes; no lists, nested objects, or YAML anchors.
2. **Strict JSON Schema extraction** — tool `inputSchema` kept to `:type`, `:properties`, and `:required`.
3. **Literal `when` clause matching** — `:settings` keys must match `when` strings exactly; no expression evaluation.

## Sponsor my open source work ♥️

This and many other projects are provided to you open source and free to use as you wish, by Peter Strömberg a.k.a. PEZ.

* https://github.com/sponsors/PEZ

## Licence

[MIT](LICENSE)

(Free to use and open source. 🍻🗽)

## Happy coding! ❤️

With or without AI 😀
