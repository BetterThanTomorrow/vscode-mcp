# vscode-mcp — AI Agent Guide

ClojureScript library that turns a VS Code extension’s existing Copilot `languageModelTools` / `chatSkills` into an MCP server (TCP in the Extension Host + stdio wrapper), with Cursor and optional ECA registration.

This repo is **not** an extension. Consumers own tool implementations, settings, port-file strategy, and when-contexts.

## Ecosystem map

| Repo | Role |
|------|------|
| **vscode-mcp** (this repo) | Socket server, stdio wrapper, Cursor/ECA lifecycle, manifest → MCP tools/resources |
| [Calva Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver) | Consumer: REPL tools, Ex wiring, BD-specific port paths / when-contexts |
| [Joyride](https://github.com/BetterThanTomorrow/joyride) | Consumer: Joyride eval tools, Joyride-specific port paths / when-contexts |

**Responsibility split**

- **Library:** start/stop socket, install wrapper, Cursor register/unregister, ECA `.eca/config.json` upsert, optional window-shard registry, `initialize` / `tools/list` / `resources/*` / `ping` from the Copilot manifest
- **Consumer:** implement `tools/call`, pass `:mcp/on-request`, settings → `create-config`, workspace-stable ECA port file, when-contexts / commands / UI; opt into the registry with `:registry/enabled?` and `:registry/custom-data+`

Do **not** look here for BD’s Ex/`app-db` or Joyride’s SCI eval — those live in the consumer repos.

## Co-development with consumers

Backseat Driver and Joyride both pin this library via git SHA, with a commented `:local/root` ready to flip:

```edn
;; In consumer deps.edn (pattern used by BD and Joyride):
io.github.betterthantomorrow/vscode-mcp {;:local/root "../vscode-mcp"
                                         :git/url "https://github.com/BetterThanTomorrow/vscode-mcp.git"
                                         :git/sha "<pinned-sha>"}
```

### Local library work (agent ↔ human)

1. **Agent** enables `:local/root "../vscode-mcp"` in the relevant consumer `deps.edn` (comment out or remove the git coords for that dep while local).
2. **Agent instructs the human** to restart the consumer’s shadow-cljs watcher task(s). The watcher is usually started by the human as a VS Code/Cursor task — agents often cannot restart it reliably. Do **not** assume a classpath refresh without that restart.
3. Develop and verify in the consumer Extension Host (F5 / consumer’s own workflow). Library unit tests: `bb test` in this repo.
4. When the library stint is done: **hand off to the human to commit and push vscode-mcp**. Agents do not push this repo unless explicitly asked.
5. After push, **human hands back to the agent** (or asks) to restore the git dep and set `:git/sha` to the new commit id in each consumer that should pick it up. Re-comment `:local/root`. Again instruct the human to restart shadow-cljs watchers after the pin change.

Ship order for library features that need consumer wiring: **library first** (inert until opted in) → pin SHA in consumers → consumer setting + wiring commits.

**ECA default:** library `:mcp/auto-register-eca?` defaults to **`false`** so a bare SHA bump stays inert. User-facing default `true` lives in the consumer’s `package.json` setting, wired into `create-config`.

**Registry default:** `:registry/enabled?` defaults to **`false`**. Consumers that want window shards pass `true` and supply `:registry/custom-data+`.

## Development in this repo

```bash
bb test   # shadow-cljs compile :test + node out/test.js
```

No Extension Host here. Integration proof is in consumer Extension Hosts and their e2e suites.

## Key namespaces

| Namespace | Purpose |
|-----------|---------|
| `vscode-mcp.core` | Public lifecycle API (`create-config`, `maybe-start!+`, `start!+`, `stop!+`, …) |
| `vscode-mcp.lifecycle` | Pure state/config helpers (no VS Code API) |
| `vscode-mcp.server` | TCP MCP socket server + port file |
| `vscode-mcp.stdio.wrapper` | Node stdio ↔ socket relay (`shadow-cljs` `:stdio-wrapper` main) |
| `vscode-mcp.manifest` | Copilot package.json → tools/resources/skills |
| `vscode-mcp.requests` | `handle-manifest-request` for non-`tools/call` methods |
| `vscode-mcp.responses` | MCP response helpers |
| `vscode-mcp.cursor` / `cursor-config` | Cursor MCP register API |
| `vscode-mcp.eca` / `eca-config` | ECA `.eca/config.json` registration |
| `vscode-mcp.policy` | Start/register/reload predicates |
| `vscode-mcp.wrapper-install` | Symlink (DEBUG) / copy (release) into `:lifecycle/wrapper-install-dir` |
| `vscode-mcp.server-readiness` | TCP probe before Cursor registration |
| `vscode-mcp.registry` | Shard schema, paths, atomic writes, dead-pid sweep |
| `vscode-mcp.registry-writer` | Process-local writer: heartbeat, debounce, generation fencing |

## Consumer wiring (contract)

### Stdio wrapper build

Consumers add a shadow-cljs build:

```edn
:stdio-wrapper {:target :node-script
                :main vscode-mcp.stdio.wrapper/main
                :output-to "dist/mcp-server.js"}  ; BD: dist/calva-mcp-server.js
```

### Request handling

Implement `:mcp/on-request` with local `tools/call`; delegate everything else to `vscode-mcp.requests/handle-manifest-request`.

`handle-manifest-request` covers `initialize`, `tools/list`, `resources/list`, `resources/read` (static skills), `resources/templates/list`, and `ping`.

Pass `:settings` when tools/skills use `when` clauses in `package.json` (literal string match only — see Limitations).

### Lifecycle

`vscode-mcp.core` drives start/stop, Cursor registration, and the manual-start dialog. Rebuild the config on every lifecycle call so settings apply on the next start. Hold one lifecycle state atom in the consumer.

| Function | Purpose |
|----------|---------|
| `init-state` | Fresh lifecycle state |
| `create-config` | Merge opts with defaults (`:server/host` → `"127.0.0.1"`; `:mcp/auto-register-eca?` → `false`; `:registry/enabled?` → `false`) |
| `running?` / `server-info` / `cursor-registered?` | Query state |
| `maybe-start!+` | Start when policy allows |
| `start!+` | Always start (+ manual-setup dialog when not silent) |
| `stop!+` | Unregister Cursor (best-effort), stop socket, unlink registry shard |
| `register-with-cursor!+` | Start if needed, then register |
| `update-registry!+` | Debounced refresh of `:registry/custom-data+` using live server-info |

Required: `:lifecycle/wrapper-install-dir`. On every start the library installs the stdio wrapper there and uses that path for manual-setup and ECA (`${env:HOME}/...` when under home).

Call `maybe-start!+` from `activate`. Deactivate with `{:lifecycle/silent? true}`.

### Window shard registry

Feature doc: [docs/registry.md](docs/registry.md).

Inert until the consumer passes `:registry/enabled? true`. On start the library sweeps dead-pid files under `~/.config/vscode-mcp/registry/windows/` (override with `:registry/dir`), writes `<server-name>-<window-id>.json`, and heartbeats `updatedAt` every 30s (`:registry/heartbeat-ms`). `stop!+` fences in-flight writes and unlinks the shard.

`:registry/custom-data+` is `(fn [state] …)` → `Promise<map>` merged onto the shard; core envelope keys (`schemaVersion`, `name`, `serverName`, `windowId`, `appId`, `workspaceRoot`, `workspaceFolder`, `hostname`, `pid`, `updatedAt`, `mcp`) cannot be overwritten. Call `update-registry!+` when that data changes (1s debounce via `:registry/debounce-ms`). `mcp` is omitted until the socket has an assigned port.

On first `on-started!+` in the Extension Host process, the library also installs registry-home support files (`README.md`, `AGENTS.md`, `bb-mcp.md`, `bb.edn`, `scripts/list_registry.clj`, `scripts/mcp.clj`) fire-and-forget from a debug sibling checkout or GitHub `master`. Grow `consumer-files` only after those paths exist; add the listing `mcp` task in the same batch as `scripts/mcp.clj`. Media sweep is hooked from `registry-writer` (`vscode-mcp.mcp-media`), not from `core/start-flow!+`. No extra `create-config` key. Details: [docs/registry.md](docs/registry.md).

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

Optional dynamic hooks: `:resource-templates+`, `:read-resource+` (`nil` → fall through to skill read), `:initialize-merge`.

| Status | Items |
|--------|-------|
| **Implemented** | Canonical `/SKILL.md`; bare alias; index; skills capability; sibling read; instruction pointers; `when` / `:settings` gating |
| **Deferred** | Archives; `mcp-resource-template`; hierarchical skill paths |

Tests: `test/vscode_mcp/manifest_test.cljs`, `test/vscode_mcp/requests_test.cljs`.

## Stdio wrapper connect-retry

- Up to 60 s budget, 500 ms interval
- Re-reads port file each attempt
- Stdin buffered until connect, then flushed
- After first connection: socket close/error exits (client respawns)
- Stdin close during wait → exit (no orphan retry)

## Cursor registration

When `:mcp/auto-register?` and Cursor MCP API are available, registration runs during start. Names are generation-suffixed: `<base>-<instance-slug>-g<generation>`.

Per register: unregister previous → `registerServer` → always `mcp.reloadClient` on success. Stale names in `workspaceState` under `vscode-mcp.cursor/registered-names`.

Stop → unregister + incremented `:lifecycle/generation` when previously registered. Next start uses a new name. TCP probe (5 s) before register; timeout warns and continues.

When auto-register is false but Cursor API exists, `maybe-start!+` still sweeps stale registrations on activate.

## ECA registration

Inert until consumer passes `:mcp/auto-register-eca? true`. Gates: ECA extension `editor-code-assistant.eca` installed (activated before write), workspace folder, port file from `server-info`.

Pass `:lifecycle/eca-port-file-uri+` for a **workspace-stable** port path (e.g. `.calva/mcp-server/port`, `.joyride/mcp-server/port`). Library always mirrors the listening port there on successful start when distinct from the primary (Cursor often uses tmpdir). Mirror deleted on stop when distinct.

Writes project-local `.eca/config.json` only; managed fields `command` / `args`; preserves siblings. Server key = `:cursor/server-name` base (not generation-suffixed). Independent of Cursor (neither rolls back the other). No deregister on stop, no ECA command/when-contexts. Idempotent when managed fields already match.

## Limitations

1. **Naive YAML frontmatter** — regex line parser; no lists, nested objects, or anchors
2. **Strict JSON Schema extraction** — tool `inputSchema` kept to `:type`, `:properties`, `:required`
3. **Literal `when` matching** — `:settings` keys must match `when` strings exactly; no `&&` / `||` / comparisons
