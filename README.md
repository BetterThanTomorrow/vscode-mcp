# vscode-mcp

A [ClojureScript](https://clojurescript.org) library for VS Code extensions that already declare Copilot **`languageModelTools`** and **`chatSkills`** in `package.json`, and want the same tools and resources available over MCP: zero-config Cursor registration, optional ECA registration, and an optional window registry so external agents can discover and connect.

The library:

1. Runs a TCP socket MCP server inside the Extension Host.
2. Bundles a Node.js `stdio` wrapper that MCP clients spawn; the wrapper relays stdin/stdout to the socket.
3. Reads your existing Copilot manifest and exposes it as MCP tools and resources.
4. Auto-registers with Cursor via [`vscode.cursor.mcp.registerServer`](https://cursor.com/docs/extension-api).
5. Optionally keeps a **window registry** at `~/.config/vscode-mcp/registry`: one entry per editor window, with discovery and attach information. Point an agent at that directory and ask it to connect. `bb list` discovers windows; `bb mcp` is the no-session attach path when the agent cannot hold MCP (`--readme` first, then `--readme-tool`). Feature doc: [docs/registry.md](docs/registry.md).

If your extension does not declare Copilot tools and skills, this library is not for you.

Agent/dev contract (lifecycle API, skill URIs, co-development with consumers): see [AGENTS.md](AGENTS.md).

## Usage

### 1. Add the Dependency

```edn
io.github.betterthantomorrow/vscode-mcp {:git/url "https://github.com/BetterThanTomorrow/vscode-mcp.git"
                                         :git/sha "f7dfcc81158f67ac8ef76e55e992daaea14d0336"}
```

Update `:git/sha` to pin a specific commit (see [releases](https://github.com/BetterThanTomorrow/vscode-mcp/releases) or `git rev-parse HEAD` in a checkout).

### 2. Configure the Stdio Wrapper Build

```edn
:stdio-wrapper {:target :node-script
                :main vscode-mcp.stdio.wrapper/main
                :output-to "dist/mcp-server.js"}
```

### 3. Wire the Manifest to MCP

Declare tools and skills in `package.json`. Implement `:mcp/on-request` with `tools/call` locally and delegate everything else to `vscode-mcp.requests/handle-manifest-request` (covers `initialize`, `tools/list`, `resources/*`, `ping`).

```clojure
(require '[vscode-mcp.manifest :as manifest]
         '[vscode-mcp.requests :as mcp-requests]
         '[vscode-mcp.responses :as responses])

(defn- settings-map []
  {"config.my-extension.someSetting" true})

(defn handle-mcp-request [{:keys [method params id] :as request} ^js context]
  (let [opts {:settings (settings-map)
              :initialize-opts {:settings (settings-map)}}]
    (case method
      "tools/call"
      (let [tool-name (:name params)
            args (:arguments params)
            allowed (manifest/tool-call-allowed? context tool-name {:settings (:settings opts)})]
        (cond
          (or (= :disabled allowed) (= :unknown allowed))
          (responses/error-response id -32601 "Unknown tool")

          :else
          ;; Dispatch to your Copilot invoke-tool implementation.
          (responses/success-response id {:content [{:type "text" :text "…"}]})))

      (mcp-requests/handle-manifest-request context request opts))))
```

Skills from `chatSkills` show up as MCP resources at `skill://{name}/SKILL.md`. Details and optional hooks (`:resource-templates+`, `:read-resource+`, `:initialize-merge`) are in [AGENTS.md](AGENTS.md).

### 4. Wire Up Lifecycle

`vscode-mcp.core` drives start/stop, Cursor registration, and the manual-start dialog. Build one config map, hold one lifecycle state atom, and rebuild the config on every lifecycle call so settings changes apply on the next start.

```clojure
(require '[vscode-mcp.core :as lifecycle]
         '["os" :as os]
         '["path" :as path]
         '["vscode" :as vscode])

(defonce !lifecycle-state (atom (lifecycle/init-state)))

(defn- read-mcp-settings []
  (let [cfg (vscode/workspace.getConfiguration "my-extension.mcp")]
    {:mcp/auto-start? (.get cfg "autoStartServer" false)
     :mcp/auto-register? (.get cfg "autoRegisterCursor" true)
     :mcp/auto-register-eca? (.get cfg "autoRegisterEca" true)
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
                                         (vscode/Uri.file (str "/tmp/my-extension-mcp/" instance-slug "/port"))
                                         (vscode/Uri.joinPath (.-extensionUri ctx) "mcp-port")))
           :lifecycle/eca-port-file-uri+ (fn [^js ctx _strategy-opts]
                                           (vscode/Uri.joinPath (.-extensionUri ctx) "mcp-port"))
           :lifecycle/request-port (fn [_ctx {:lifecycle/keys [cursor-mode?]}]
                                     (if cursor-mode? 0 (:server/request-port (read-mcp-settings))))
           :lifecycle/wrapper-install-dir (path/join (os/homedir) ".config" "my-extension")
           :lifecycle/on-running-changed (fn [running? _info]
                                           ;; Sync VS Code when-contexts, status bar, etc.
                                           )})))

(defn activate [^js context]
  (-> (lifecycle/maybe-start!+ (build-lifecycle-config context) @!lifecycle-state true)
      (.then #(reset! !lifecycle-state %))))

(defn deactivate []
  (-> (lifecycle/stop!+ (build-lifecycle-config nil) @!lifecycle-state {:lifecycle/silent? true})
      (.then #(reset! !lifecycle-state %))))
```

Call `maybe-start!+` from `activate`. Use `start!+` / `stop!+` / `register-with-cursor!+` for commands. `:lifecycle/wrapper-install-dir` is required (symlink in DEBUG, copy in release).

### Optional window registry

Pass `:registry/enabled? true` to join the registry. Supply `:registry/custom-data+` for provider discovery (for example REPL sessions) and call `update-registry!+` when that data changes. Wiring: [docs/registry.md](docs/registry.md).

### Optional ECA registration

Library default for `:mcp/auto-register-eca?` is **false**. Pass `true` from your setting if you want ECA `.eca/config.json` registration. Full Cursor/ECA behavior: [AGENTS.md](AGENTS.md).

## Reference Implementations

[Calva Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver) and [Joyride](https://github.com/BetterThanTomorrow/joyride).

## Limitations

1. **Naive YAML frontmatter parsing** — regex line parser for skill frontmatter; no lists, nested objects, or YAML anchors.
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
