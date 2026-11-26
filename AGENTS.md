# vscode-mcp — AI Agent Guide

ClojureScript library that turns a VS Code extension’s existing Copilot `languageModelTools` / `chatSkills` into an MCP server (TCP in the Extension Host + stdio wrapper), with Cursor and optional ECA registration.

This repo is **not** an extension. Consumers own tool implementations, settings, and when-contexts. The library writes the port file under `~/.config/vscode-mcp/port-files/`.

Ask yourself: Will this and the tests work on Windows too?

## Ecosystem map

| Repo | Role |
|------|------|
| **vscode-mcp** (this repo) | Socket server, stdio wrapper, Cursor/ECA lifecycle, manifest → MCP tools/resources |
| [Calva Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver) | Consumer: REPL tools, Ex wiring, when-contexts |
| [Joyride](https://github.com/BetterThanTomorrow/joyride) | Consumer: Joyride eval tools, when-contexts |

**Responsibility split**

- **Library:** start/stop socket, port file under `~/.config/vscode-mcp/port-files/`, install wrapper, install the skills into the skills home, Cursor register/unregister, ECA `.eca/config.json` upsert, optional window registry, `initialize` / `tools/list` / `resources/*` / `ping` from the Copilot manifest
- **Consumer:** implement `tools/call`, pass `:mcp/on-request`, settings → `create-config`, when-contexts / commands / UI from the server's state; opt into the registry with `:registry/enabled?` and `:registry/custom-data`

Do **not** look here for BD’s Ex/`app-db` or Joyride’s SCI eval — those live in the consumer repos.

## Co-development with consumers

[Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver) and [Joyride](https://github.com/BetterThanTomorrow/joyride) share a four-layer co-dev tower. Do not drop or rename an npm script that a VS Code task points at.

1. **Shipped truth** — main `:deps` git pin (`:git/url` + `:git/sha`). Clones and CI resolve without a sibling checkout. Do **not** flip the main dep to `:local/root`.
2. **Local overlay** — `:local-dev` alias with `:override-deps` to `{:local/root "../vscode-mcp"}`.
3. **npm scripts** — pin-first: `watch` and `compile` use the git pin; `watch:local` activates `:local-dev`. `compile`, `release`, packaging, and CI do not activate `:local-dev`. Joyride on Windows: `watchwin` / `watchwin:local` (same pin vs local split).
4. **VS Code tasks** (`.vscode/tasks.json`) — human intent: default **Watch** → `watch:local`; **Watch (pinned vscode-mcp)** → `watch`. Every task `"script"` value must exist in that package's `package.json` `"scripts"`. Day-to-day co-dev uses the **Watch** task, not `npm run watch`.

```edn
;; Main dep — always git pin:
io.github.betterthantomorrow/vscode-mcp {:git/url "https://github.com/BetterThanTomorrow/vscode-mcp.git"
                                         :git/sha "<pinned-sha>"}

;; Alias — local sibling checkout:
:aliases {:local-dev {:override-deps {io.github.betterthantomorrow/vscode-mcp {:local/root "../vscode-mcp"}}}}
```

```bash
npm run watch              # git pin (VS Code task: Watch (pinned vscode-mcp))
npm run watch:local        # -M:local-dev (VS Code task: Watch)
npm run compile            # git pin — CI/package
```

Joyride also has `watchwin` / `watchwin:local` on Windows (same pin vs local split).

### Local library work (agent ↔ human)

1. **Agent or human** uses the consumer `:local-dev` alias (default **Watch** task → `watch:local`; Joyride on Windows: `watchwin:local`). Use **Watch (pinned vscode-mcp)** / `npm run watch` (Joyride: `watchwin`) on the SHA pin. Other consumer aliases (e.g. Backseat Driver `:e2e-test-joyride` for clojure-lsp / e2e Joyride sources) stay orthogonal — they must not put `:local/root` on vscode-mcp; compose with `:local-dev` only when intentional (`-A:local-dev:e2e-test-joyride`). **Instruct the human** to restart the consumer's shadow-cljs watcher task(s). Agents often cannot restart that task reliably. Do **not** assume a classpath refresh without that restart.
2. Develop and verify in the consumer Extension Host (F5 / consumer’s own workflow). Library unit tests: `bb test` in this repo.
3. **Commit vscode-mcp first** (library repo). Agents do **not** push unless explicitly asked.
4. **Then** set each consumer’s main `:deps` `:git/sha` to that vscode-mcp commit id, and **commit** the consumer (`deps.edn` pin bump, plus any consumer wiring). Do this for Backseat Driver and for Joyride when that consumer should pick up the library. Do not commit the consumer pin before the vscode-mcp commit exists.
5. **Hand off to the human** with an explicit push order: push **vscode-mcp first**, then **Backseat Driver** (and **Joyride** the same way when it was bumped). Instruct the human to restart consumer watchers after the pin change when they switch off `:local-dev` / verify the pin.

Ship order for library features that need consumer wiring: commit the library (inert until opted in), commit the consumer pin and any consumer wiring, then the human pushes the library and then the consumers.

**ECA default:** library `:mcp/auto-register-eca?` defaults to **`false`** so a bare SHA bump stays inert. User-facing default `true` lives in the consumer’s `package.json` setting, wired into `create-config`.

**Registry default:** `:registry/enabled?` defaults to **`false`**. Consumers that want window entries pass `true` and supply `:registry/custom-data`.

## Development in this repo

```bash
bb test   # shadow-cljs compile :stdio-wrapper and :test + node out/test.js, then the Babashka tests
```

No Extension Host here. Integration proof is in consumer Extension Hosts and their e2e suites. The test build can't load a namespace that requires `vscode`, such as `core` and `lifecycle-fxs`; those are exercised from a consumer's unit tests (Backseat Driver stubs `vscode`) and e2e suite.

Promise code is written as async functions, `(defn ^:async f+ [...] (await ...))`, with no promise library. ClojureScript 1.12.145 and shadow-cljs 3.5.14 compile them:

- Inside an async function a promise is awaited. The compiler awaits the expressions it wraps in a function of their own: a `do`, `let` or `try` used as a value, and a call of a local that isn't a `fn` literal when its arguments aren't plain values. A promise from such an expression can't be handed on, or chained with `.then`. A helper that takes the function and its arguments, and makes the call itself, gets the promise.
- A function that only hands a promise on stays a plain function.
- `await` takes any thenable. VS Code's `document.save()`, `workspace.applyEdit` and `window.showInformationMessage` return thenables that aren't native promises.
- A test of async code is `(deftest ^:async …)` that awaits what it checks, one async test per `deftest`.

The tests in `test-process/` start the compiled stdio wrapper, `out/stdio-wrapper.js`, as a process against a socket server in the test, for what depends on timing: stdin ending while it waits, giving up, a server that appears late, a connection that breaks. The directory is on the classpath through the `:shadow-cljs` alias only, so the consumers' test runs don't pick those tests up.

## Key namespaces

| Namespace | Purpose |
|-----------|---------|
| `vscode-mcp.core` | Public lifecycle API (`create-config`, `maybe-start!+`, `start!+`, `stop!+`, …). `maybe-start!+`, `start!+`, `stop!+` and `register-with-cursor!+` each keep the config, gather what the decisions need from the editor, and dispatch one action. Holds the state: the servers' entries by server name |
| `vscode-mcp.event-handler` | The loop the lifecycle runs on. A copy of Backseat Driver's `event_handler.cljs`, the same text apart from the namespace name, so a change to one goes to the other |
| `vscode-mcp.lifecycle-axs` | The start, stop and Cursor registration's decisions, as pure actions `:lifecycle/ax.*`, and the state changes that are reported |
| `vscode-mcp.lifecycle-fxs` | The effects `:lifecycle/fx.*`, async functions that resolve to data, also when what they did failed. Keeps the live objects by server name: the config as handed over, and the server that listens |
| `vscode-mcp.lifecycle` | Pure state/config helpers (no VS Code API): the server's state as data |
| `vscode-mcp.lifecycle-turns` | The entry points' calls for one server, one after the other, each waiting for the one before it for a limited time: the call in progress by server name |
| `vscode-mcp.port-file` | Port file under `~/.config/vscode-mcp/port-files/<serverName>-<windowId>.port` + stale sweep |
| `vscode-mcp.server` | TCP MCP socket server + port file I/O |
| `vscode-mcp.stdio.wrapper` | Node stdio ↔ socket relay (`shadow-cljs` `:stdio-wrapper` main). With no port argument, it connects to the caller's window. The shell: it dispatches what happens as actions and performs effects |
| `vscode-mcp.stdio.wrapper-axs` | The wrapper's decisions: `handle-action`, from the wrapper's state and one action to the next state and the effects |
| `vscode-mcp.stdio.window-lookup` | The caller's window for the wrapper: `VSCODE_MCP_EXTENSION_HOST`, process ancestry, `VSCODE_MCP_WINDOW_ID`, the working directory, live entries |
| `vscode-mcp.manifest` | Copilot package.json → tools/resources/skills. `manifest-data` reads the manifest into data, and the other functions take that map |
| `vscode-mcp.requests` | `handle-manifest-request` for non-`tools/call` methods |
| `vscode-mcp.responses` | MCP response helpers |
| `vscode-mcp.cursor` / `cursor-config` | Cursor MCP register API |
| `vscode-mcp.eca` / `eca-config` | ECA `.eca/config.json` registration |
| `vscode-mcp.policy` | Start/register/reload predicates |
| `vscode-mcp.wrapper-install` | Symlink (DEBUG) / copy (release) into `:lifecycle/wrapper-install-dir` |
| `vscode-mcp.skills-install` | The manifest's skills into the skills home, `~/.config/vscode-mcp/skills/<extension-name>/<skill-name>/`: link (DEBUG) / copy (release) |
| `vscode-mcp.server-readiness` | TCP probe before Cursor registration |
| `vscode-mcp.registry` | Entry schema, paths, atomic writes, dead-pid sweep |
| `vscode-mcp.registry-writer` | The window's entry as data, written at start, after a change (debounced) and on the heartbeat |

## Consumer wiring (contract)

### Stdio wrapper build

Consumers add a shadow-cljs build:

```edn
:stdio-wrapper {:target :node-script
                :main vscode-mcp.stdio.wrapper/main
                :output-to "dist/mcp-server.js"}  ; BD: dist/calva-mcp-server.js
```

### Request handling

Implement `:mcp/on-request` with local `tools/call`; delegate everything else to `vscode-mcp.requests/handle-manifest-request`, which takes the manifest as data (`manifest/manifest-data extension-context`, read once or per request). The handler returns a response map or a promise of one, and nil or a promise of nil for what gets no answer. Any thenable will do, native or not, such as promesa's.

`handle-manifest-request` covers `initialize`, `tools/list`, `resources/list`, `resources/read` (static skills), `resources/templates/list`, and `ping`.

Stock `tools/list` is `name` / `description` (`modelDescription`) / `inputSchema`. Pass `includeUserDescription: true` in the request params to add `userDescription` from package.json. Default (missing/false) is today's shape. `bb mcp --readme` is the client that sends that arg.

Pass `:settings` when tools/skills use `when` clauses in `package.json` (literal string match only — see Limitations).

### Lifecycle

`vscode-mcp.core` runs the server from the config a consumer hands it. The start, the stop and the Cursor registration run on the loop in `vscode-mcp.event-handler`: an entry point keeps the config, and dispatches one action that decides, and the effects do the work and read the latest config that was kept. The consumer gets the server's state as plain data: as what each lifecycle function resolves to, and through `:mcp/on-server-changed`, which is called with the server's state when a dispatch changed it. Rebuild the config on every call so settings apply on the next start. A consumer's build needs ClojureScript 1.12.145 or later.

| Function | Purpose |
|----------|---------|
| `create-config` | Merge opts with defaults (`:server/host` → `"127.0.0.1"`; `:mcp/auto-register-eca?` → `false`; `:registry/enabled?` → `false`) |
| `maybe-start!+ config` | Start when policy allows, without a message |
| `start!+ config` | Start, and show the manual-setup dialog |
| `stop!+ config` / `stop!+ config {:lifecycle/silent? true}` | Stop the socket and delete the port file, unregister from Cursor (best-effort), then unlink the registry file. A server that is still starting is left to start |
| `register-with-cursor!+ config` | Start if needed, then register |
| `update-registry!+ config` | After the lifecycle call before it, take the config's `:registry/custom-data` for the running server's entry, written after the debounce time |
| `server-state config` | The server's state, as below |

The config, beside the settings: `:vscode/extension-context`, `:server/name` (the registry entry, the port file and the ECA entry are named by it), `:wrapper/script-relative-path`, `:lifecycle/wrapper-install-dir` (required: on every start the library installs the stdio wrapper there and uses that path for manual-setup and ECA), `:manual-setup/extension-name`, `:server/request-port` (the library listens on port 0 when it registers with Cursor), `:registry/custom-data`, and three functions: `:mcp/on-request`, `:mcp/on-log` and `:mcp/on-server-changed`.

The server's state, a plain map:

```clojure
{:server/running? true
 :server/starting? false
 :server/stopping? false
 :server/host "127.0.0.1"
 :server/assigned-port 49311
 :server/instance-slug "ws-bxnohh"
 :server/port-file-path "/Users/me/.config/vscode-mcp/port-files/my-server-ws-bxnohh.port"
 :server/error nil                           ; the message of a start that failed
 :lifecycle/registered-name nil              ; the server's name in Cursor
 :lifecycle/registration-error nil}          ; {:reason :cursor-api-unavailable} when a registration failed
```

A failure is in the data. A consumer shows its own messages for `:server/error` and `:lifecycle/registration-error`, and computes its when-contexts from the state.

A start fails in one place, the step that starts the server: the wrapper can't be installed, the server can't listen, or the port file can't be written. That leaves no server, and `:server/error` says why. A Cursor registration that fails or throws during a start leaves the server running, with `:lifecycle/registration-error`. A failed ECA registration, registry entry or skill install is logged. The entry points run the calls for one server one after the other: each waits until the call before it has settled, also when it rejected, so a start, stop or registration that comes in while a start is in progress resolves to the outcome of its own operation, and the custom data that `update-registry!+` gets during a start is in the entry after it. A call that hasn't settled after 30 seconds, such as one that awaits an editor API that never answers, is waited for no longer: the call after it logs that, and runs, and the actions' guards protect the state. The silent stop at deactivation waits 3 seconds, since VS Code gives deactivation only a few. A start that finds the server running or starting starts nothing, and resolves to the server's state.

Call `maybe-start!+` from `activate`. Deactivate with `{:lifecycle/silent? true}`.

### Window registry

Feature doc: [docs/registry.md](docs/registry.md).

Inert until the consumer passes `:registry/enabled? true`. On start the library sweeps dead-pid files under `~/.config/vscode-mcp/registry/windows/` (override with `:registry/dir`), writes `<server-name>-<window-id>.json`, heartbeats `updatedAt` every 30s (`:registry/heartbeat-ms`), and sets `VSCODE_MCP_WINDOW_ID` to the window's id in its terminals. `stop!+` forgets the entry, so no timer writes it again, and unlinks the registry file.

`:registry/custom-data` is a map merged onto the entry; core envelope keys (`schemaVersion`, `name`, `serverName`, `windowId`, `appId`, `workspaceRoot`, `workspaceFolder`, `hostname`, `pid`, `updatedAt`, `mcp`) cannot be overwritten. The entry written at start has the data in the newest config that was kept, which may be a later call's. Call `update-registry!+` with a config that has the new data when it changes (1s debounce via `:registry/debounce-ms`). `mcp` is omitted until the socket has an assigned port.

On first `on-started!+` in the Extension Host process, the library also installs registry-home support files (`README.md`, `AGENTS.md`, `bb-mcp.md`, `bb.edn`, `scripts/list_registry.clj`, `scripts/mcp.clj`, `scripts/mcp_briefing.clj`) fire-and-forget from a debug sibling checkout or GitHub `master`. Grow `consumer-files` only after those paths exist; add the listing `mcp` task in the same batch as `scripts/mcp.clj`. Installed `bb-mcp.md` starts agents on `--readme` / `--readme-tool`. Media sweep is hooked from `registry-writer` (`vscode-mcp.mcp-media`), not from the start's effects. No extra `create-config` key. Details: [docs/registry.md](docs/registry.md).

## Skill resources (SEP-2640 subset)

| Topic | Contract |
|-------|----------|
| Canonical URI | `skill://{name}/SKILL.md` |
| Read alias | `skill://{name}` — `resources/read` only; not listed |
| `resources/list` | One entry per enabled skill (SKILL.md only). Siblings and `skill://index.json` **not** listed |
| `resources/read` — index | `skill://index.json` — same `when` / `:settings` gating as list |
| `resources/read` — siblings | Under skill dir; path-safe (no `..` / escape); best-effort symlinks |
| mimeType | `.md` → `text/markdown`; other → `text/plain`; index → `application/json` |
| Initialize capability | `capabilities.extensions["io.modelcontextprotocol/skills"] = {}` |
| Initialize instructions | Full `skill://…/SKILL.md` URIs; mention `skill://index.json` |

`:initialize-merge` **deep-merges** `:capabilities` (other keys shallow-merge).

Optional dynamic hooks: `:resource-templates+`, `:read-resource+` (`nil` → fall through to skill read), `:initialize-merge`. A `+` hook returns a value or any thenable of one.

| Status | Items |
|--------|-------|
| **Implemented** | Canonical `/SKILL.md`; bare alias; index; skills capability; sibling read; instruction pointers; `when` / `:settings` gating |
| **Deferred** | Archives; `mcp-resource-template`; hierarchical skill paths |

Tests: `test/vscode_mcp/manifest_test.cljs`, `test/vscode_mcp/requests_test.cljs`.

## Skills home

Every server start installs the skills the manifest declares into the skills home, `~/.config/vscode-mcp/skills`, after the wrapper's install: `<skills-home>/<extension-name>/<skill-name>/`, named by the manifest's `name` and each skill's frontmatter `name`, as the skill's URI has them. A development build (`goog.DEBUG`) links each skill's folder, a junction on Windows, and a release copies it. The last start wins, also between editors with different versions of the extension. Every skill is installed, whatever its `when` clause says, since the folder serves the whole machine and not one window's settings.

`vscode-mcp.skills-install` makes the install a plan of steps, as data, and performs them. Each skill is linked or copied under a temporary name, a dot name next to the old one, and renamed over it, so a link someone made to it keeps working. An entry the manifest no longer declares is removed, a link by unlinking it, never by a recursive remove, which on Windows can follow a junction into what it points at; when `<skills-home>/<extension-name>` is itself a link, to a folder that may hold more, those entries are left. A temporary entry older than a minute is what an install left behind, a crash or a removal that failed, and is removed; one that is younger may be another window's install in progress. A blank extension name installs nothing. A step that fails is logged through `:mcp/on-log` and doesn't fail the start, and a cleanup that fails is logged apart from the install's result. Two windows that start at once can make a rename fail; the other window's skill is then in place.

The library owns the skills home and replaces what is in it: it is the exception to putting your own files in `~/.config/vscode-mcp`. Override the folder with `:skills/dir` (tests). Tests: `test/vscode_mcp/skills_install_test.cljs`.

## Stdio wrapper connect-retry

- Up to 60 s budget, 500 ms interval
- Re-reads port file each attempt
- Stdin buffered until connect, then flushed
- After first connection: socket close/error exits (client respawns)
- Stdin close during wait → exit (no orphan retry)

## Cursor registration

When `:mcp/auto-register?` and Cursor MCP API are available, registration runs during start. Names are generation-suffixed: `<base>-<instance-slug>-g<generation>`.

Per register: unregister previous → `registerServer` → always `mcp.reloadClient` on success. Stale names in `workspaceState` under `vscode-mcp.cursor/registered-names`.

Stop → after the socket is closed and the port file deleted, unregister + incremented `:lifecycle/generation` when previously registered. Next start uses a new name. TCP probe (5 s) before register; timeout warns and continues.

When auto-register is false but Cursor API exists, `maybe-start!+` still sweeps stale Cursor registrations on activate.

## Port file

Path is `~/.config/vscode-mcp/port-files/<serverName>-<windowId>.port`. Created after listen; deleted on stop; exists while Running. `maybe-start!+` sweeps that directory on activate: `.port` files whose port is not accepting (or whose content is invalid) are deleted. Override the directory with `:port-file/dir` (tests). Registry `portFilePath` uses this path. `:lifecycle/port-file-uri+` is unused.

## ECA registration

Inert until consumer passes `:mcp/auto-register-eca? true`. Gates: ECA extension `editor-code-assistant.eca` installed (activated before write), workspace folder, port file from `server-info`.

The managed entry is the same on every machine. `node -e` loads the wrapper with a one-line script that joins its path segments under `os.homedir()` when it starts, and the wrapper's file name follows, which the wrapper matches against entries' `mcp.wrapperPath`. With no port file, the wrapper connects to the caller's window (see [docs/registry.md](docs/registry.md#the-callers-window)):

```json
"calva-backseat-driver": {
  "command": "node",
  "args": ["-e",
           "require(require('path').join(require('os').homedir(),'.config','calva','backseat-driver','calva-mcp-server.js'))",
           "calva-mcp-server.js"]
}
```

The script holds no `$`, `"`, space or tab, so ECA's `$VAR` expansion leaves it alone and Java's `ProcessBuilder` passes it on Windows without quoting. Characters other than letters, digits and `._/:-` in the path are written as `\uXXXX` escapes. A wrapper outside the home directory is required by its absolute path, with forward slashes, and that entry names the machine. With `:registry/enabled?` false the wrapper can't find the caller's window, so the args end with the library port file's path (`fsPath`, under `~/.config/vscode-mcp/port-files/`) and the host, and the entry names the machine too. The library does not write a workspace mirror (no `.calva` / `.joyride` MCP port file). Discovery for agents is the window registry; manual stdio configs point at the library port file path.

Writes project-local `.eca/config.json` only; managed fields `command` / `args`; preserves siblings. Server key = `:server/name` base (not generation-suffixed). Independent of Cursor (neither rolls back the other). No deregister on stop, no ECA command/when-contexts. Idempotent when managed fields already match. Tests: `test/vscode_mcp/eca_config_test.cljs`, and `test-bb/eca_entry_test.clj`, which starts the `node -e` commands that `eca_config_test.cljs` writes to `.tmp/eca-entry-fixture.json` through Java's process API, as ECA does, so `bb test-bb` alone needs a `bb test` run first.

## Limitations

1. **Naive YAML frontmatter** — regex line parser; no lists, nested objects, or anchors. A value quoted as a whole is read without its quotes, with `''` in a single-quoted value and `\"` and `\\` in a double-quoted one read as YAML reads them
2. **Strict JSON Schema extraction** — tool `inputSchema` kept to `:type`, `:properties`, `:required`
3. **Literal `when` matching** — `:settings` keys must match `when` strings exactly; no `&&` / `||` / comparisons
