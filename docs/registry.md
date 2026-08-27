# Window registry

External AI harnesses (and humans) can discover a VS Code window's MCP server and attach to it: spawn the stdio wrapper, or write the client's MCP config from the same fields.

vscode-mcp is one MCP server per editor window. The registry is a file-backed **registry home** at `~/.config/vscode-mcp/registry`. Point an agent at that directory and ask it to connect.

The user-facing copy for that directory is [`assets/registry-content/README.md`](../assets/registry-content/README.md). This document is the library contract: shards, writer, listing, and consumer wiring.

The shard writer is in the library (`vscode-mcp.registry`, `vscode-mcp.registry-writer`). Registry home support files (`bb list`, installed docs) are specified under Listing and Install of support files. Canonical sources live in `assets/registry-content/`.

## Registry home

Registry home is a package. vscode-mcp owns the tree and overwrites files there. Put custom files in the parent directory, `~/.config/vscode-mcp`.

```
~/.config/vscode-mcp/registry/        registry home
  README.md                           lodestar; installed as written
  AGENTS.md                           attach recipe
  bb.edn                              `bb list` (`:paths ["scripts"]`)
  scripts/list_registry.clj
  windows/*.json                      one shard per window
```

Default shard directory: `~/.config/vscode-mcp/registry/windows/`. Override with `:registry/dir` (tests).

## Opt-in (extension consumers)

Inert until the consumer passes `:registry/enabled? true` in `create-config` (library default `false`).

```clojure
(lifecycle/create-config
  {:registry/enabled? true
   :registry/custom-data+ (fn [_state]
                            ;; Promise<map> merged onto the shard.
                            (p/resolved {:sessions [...]}))
   ;; ...
   })
```

Call `(vscode-mcp.core/update-registry!+ config)` when that custom data changes. The library debounces 1000 ms (`:registry/debounce-ms`).

Protected envelope keys cannot be overwritten by custom data: `schemaVersion`, `name`, `serverName`, `windowId`, `workspaceRoot`, `hostname`, `pid`, `updatedAt`, `mcp`.

Other `create-config` keys: `:registry/heartbeat-ms` (default 30000), `:registry/dir` (optional).

Backseat Driver opts in and puts compact REPL sessions on the shard. Joyride can opt in with its own custom keys. Discovery fields vary by provider; attach fields (`mcp`) are shared.

## Shard files

Filename: `<server-name>-<window-id>.json` (for example `backseat-driver-ws-1a2b3c.json`).

`windowId` is `vscode-mcp.cursor-config/instance-slug`: `ws-<hash>` with a workspace folder, `win-<hash>` without.

```json
{
  "schemaVersion": 1,
  "name": "backseat-driver-ws-1a2b3c",
  "serverName": "backseat-driver",
  "windowId": "ws-1a2b3c",
  "workspaceRoot": "/Users/pez/Projects/my-app",
  "hostname": "Pappas-data",
  "pid": 48291,
  "updatedAt": "2026-08-25T09:30:00.000Z",
  "mcp": {
    "host": "127.0.0.1",
    "port": 1664,
    "portFilePath": "/Users/pez/.cursor/tmp/calva-mcp-server/ws-1a2b3c/port",
    "wrapperPath": "/Users/pez/.config/calva/backseat-driver/calva-mcp-server.js"
  }
}
```

- `workspaceRoot` is the workspace folder's absolute `fsPath`, omitted when there is no folder.
- `hostname` is `os.hostname()` as the OS reports it.
- `mcp` is present only when the socket has an assigned port. `mcp.host` defaults to `127.0.0.1`. `portFilePath` is the live port file (`:server/port-file-uri`). `wrapperPath` is the installed stdio wrapper.
- Consumer keys (for example `sessions`) sit beside the envelope.

A shard is live when its `pid` is running **and** `updatedAt` is younger than 60 seconds.

## Writer

On MCP start (`registry-writer/on-started!+`), when `:registry/enabled?`:

1. Sweep `windows/` for JSON shards and leftover `.tmp` files whose `pid` is dead.
2. Write this window's shard (envelope plus `:registry/custom-data+`).
3. Heartbeat: rewrite the last payload with a fresh `updatedAt` every 30s. Heartbeat does not re-run `custom-data+`.

`stop!+` increments a generation (in-flight `custom-data+` cannot write a stale shard), clears timers, and unlinks this window's shard.

Writes are atomic: temp file `<shard>.<pid>.<rand>.tmp` in the same directory, then `rename`. Windows `EPERM`/`EBUSY` unlinks the destination and retries.

Tests: `test/vscode_mcp/registry_test.cljs`, `test/vscode_mcp/registry_writer_test.cljs`.

## Listing

`bb list` (installed under registry home) reads `windows/*.json` and prints discovery plus attach. Prefer it strongly enough that a missing `bb` gets installed ([Babashka](https://github.com/babashka/babashka#installation)) rather than skipping straight to the JSON files. Reading the shards is valid; they are what the lister parses.

Default: live shards only. `--stale` includes the rest. `--json` and `--edn` print the same snapshot as maps. Stdout only.

Text example (Backseat Driver `sessions` pretty-printed; other providers' custom keys print too):

```
backseat-driver  ws-xf11vn  /Users/pez/Projects/backseat-driver/test-projects/example
  host: Pappas-data.local  age 3s
  mcp:  node ~/.config/calva/backseat-driver/calva-mcp-server.js <portFile> 127.0.0.1
  sessions:
    clj     .
    cljs    .  :app #8 "Chrome",  :tui #10 "Node"
```

Use the list to pick the relevant server. The listing contains `workspaceRoot` and other, provider-dependent information needed for discovery and connect.

Then attach: `node <wrapperPath> <portFilePath> <host>`, or write the client's MCP config from those fields. After attach, query MCP for live details. Shard discovery can lag; MCP is current.

`watch-registry` (`bb watch-registry` in this repo) is a development event stream over the same shards. It stays in the vscode-mcp repo.

### Installed AGENTS.md

The installed `AGENTS.md` is the attach recipe. It says:

1. You were pointed at registry home.
2. Read `README.md` and `AGENTS.md`. This directory is overwritten. Put custom files in `~/.config/vscode-mcp`.
3. When `bb.edn` is present, prefer `bb list` (or `--edn` / `--json`). When only first-fail stubs are on disk, read `windows/*.json`.
4. If `bb` is missing, install Babashka and retry `bb list`. Prefer that over reading the JSON files.
5. Use the list to pick the relevant server. The listing contains `workspaceRoot` and other, provider-dependent information needed for discovery and connect.
6. Attach from `mcp`, or write client config from the same fields.
7. Query MCP for live details.

Pez authors `README.md`. Pez authors `AGENTS.md` to match this recipe.

## Install of support files

Canonical sources in this repo:

```
assets/registry-content/
  README.md
  AGENTS.md
  bb.edn
  scripts/list_registry.clj
  fallback/README.md          embed-only; not installed as a directory
  fallback/AGENTS.md
```

No extra `create-config` key. Requires `:registry/enabled?`. Hook: first `registry-writer/on-started!+` in this Extension Host process. Fire-and-forget: socket start does not wait. Failures go to `:mcp/on-log`. Heartbeats and `update-registry!+` do not install.

Source order:

1. Debug (`goog.DEBUG`): `{extensionPath}/../vscode-mcp/assets/registry-content/` when that directory exists (sibling checkout).
2. Else GitHub raw, branch `master`: the four consumer files from `https://raw.githubusercontent.com/BetterThanTomorrow/vscode-mcp/master/assets/registry-content/`.

The full listing tree is not shipped in the VSIX. Debug sibling install copies only the four consumer files, not `fallback/`. Write when bytes differ.

A process-local flag records a **successful** fetch for this session. Stop/start MCP in the same window does not fetch again after success.

Twenty Extension Hosts may each fire once. One write wins; the rest see matching bytes.

### First GitHub miss

Ongoing offline use is out of scope.

When GitHub fails and a previous successful install is present (`bb.edn` or the real README): leave the destination unchanged.

When GitHub fails and nothing usable is on disk yet: write embedded stub `README.md` and `AGENTS.md` at registry home (not inside `windows/`). Those two files are pez-authored in `assets/registry-content/fallback/` and embedded in the compiled library. The stub README says GitHub was unreachable, retry when it is available, and point the agent at `windows/` in the meantime. The stub `AGENTS.md` tells the agent to read the shards and attach from `mcp`.

A failed fetch does not set the success flag, so the next `on-started!+` retries GitHub. A later successful fetch replaces the stubs.

## Consumer skill pointer

When a user asks how to connect an external harness to this workstation's MCP: registry home is `~/.config/vscode-mcp/registry`. Point the agent at that directory. Keep the extension skill short; installed `AGENTS.md` owns the steps.
