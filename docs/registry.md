# Window registry

External AI harnesses (and humans) can discover a VS Code window's MCP server and attach to it: spawn the stdio wrapper, write the client's MCP config from the same fields, or run `bb mcp` from registry home when they cannot hold a session.

vscode-mcp is one MCP server per editor window. The registry is a file-backed **registry home** at `~/.config/vscode-mcp/registry`. Point an agent at that directory and ask it to connect.

The user-facing copy for that directory is [`assets/registry-content/README.md`](../assets/registry-content/README.md). This document is the library contract: shards, writer, listing, and consumer wiring.

The shard writer is in the library (`vscode-mcp.registry`, `vscode-mcp.registry-writer`). Registry home support files (`bb list`, `bb mcp`, installed docs) are specified under Listing and Install of support files. Canonical sources live in `assets/registry-content/`.

## Registry home

Registry home is a package. vscode-mcp owns the tree and overwrites files there. Put custom files in the parent directory, `~/.config/vscode-mcp`.

```
~/.config/vscode-mcp/registry/        registry home
  README.md                           lodestar; installed as written
  AGENTS.md                           session attach; points at bb-mcp.md when you cannot hold MCP
  bb-mcp.md                           `bb mcp` recipe
  bb.edn                              `bb list` and `bb mcp` (`:paths ["scripts"]`)
  scripts/list_registry.clj
  scripts/mcp.clj
  scripts/mcp_briefing.clj
  windows/*.json                      one shard per window

~/.config/vscode-mcp/mcp-media/       sibling of registry home
  <serverName>-<windowId>/            image/audio files from `bb mcp` (same stem as the shard)
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

Protected envelope keys cannot be overwritten by custom data: `schemaVersion`, `name`, `serverName`, `windowId`, `appId`, `workspaceRoot`, `workspaceFolder`, `hostname`, `pid`, `updatedAt`, `mcp`.

Other `create-config` keys: `:registry/heartbeat-ms` (default 30000), `:registry/dir` (optional).

Backseat Driver opts in and puts compact REPL sessions on the shard. Joyride can opt in with its own custom keys. Discovery fields vary by provider; attach fields (`mcp`) are shared.

## Shard files

Filename: `<server-name>-<window-id>.json` (for example `calva-backseat-driver-ws-1a2b3c.json`).

`windowId` is `vscode-mcp.cursor-config/instance-slug`: `ws-<hash>` of the editor `uriScheme` plus the `.code-workspace` file, or the first folder path if there is no workspace file. Empty windows use `win-<hash>` of `uriScheme` plus the Extension Host pid, so extensions in that window share a slug.

`:cursor/server-name` is the consumer’s package.json `name` (Backseat Driver: `calva-backseat-driver`). That string is also Cursor’s register base, the ECA config key, the shard filename prefix, and the media-dir prefix. Cursor names are generation-suffixed (`<base>-<slug>-gN`). After a rename, an untracked old `backseat-driver-…-gN` entry can remain in workspaceState for the human to remove.

```json
{
  "schemaVersion": 1,
  "name": "calva-backseat-driver-ws-1a2b3c",
  "serverName": "calva-backseat-driver",
  "windowId": "ws-1a2b3c",
  "appId": "cursor",
  "workspaceRoot": "/Users/pez/Projects/my-app.code-workspace",
  "workspaceFolder": "/Users/pez/Projects/my-app",
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

- `appId` is the editor CLI slug (`cursor`, `code`, `code-insiders`) from `{appRoot}/product.json` `applicationName`, or `uriScheme` if that file is missing.
- `workspaceRoot` is the path the instance slug hashes: the `.code-workspace` file, or the first folder if there is no workspace file. Omitted when there is no folder.
- `workspaceFolder` is the first folder path when the window has folders. `bb list` uses it to relativize session `projectRoot` (else the parent of a `.code-workspace` `workspaceRoot`). That directory can differ from `workspaceRoot` in a multi-root window.
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

## Media files

`bb mcp` writes image, audio, and resource `blob` parts under `~/.config/vscode-mcp/mcp-media/<serverName>-<windowId>/` (sibling of registry home; same stem as the shard file). The CLI process exits after the write; the Extension Host owns the sweep (`vscode-mcp.mcp-media`, hooked from `registry-writer/on-started!+` / `on-stopping!`).

On MCP start: wait 10 minutes, then delete any file in that window’s dir whose mtime is older than 10 minutes. Missing files are not an error. Repeat while the socket is up (interval 60 s). On stop: clear timers, leave the directory. Empty dirs are not created on start.

## Listing

`bb list` (installed under registry home) reads `windows/*.json` and prints discovery plus attach. Prefer it strongly enough that a missing `bb` gets installed ([Babashka](https://github.com/babashka/babashka#installation)) rather than skipping straight to the JSON files. Reading the shards is valid; they are what the lister parses.

Default: live shards only. `--stale` includes the rest. `--json` and `--edn` print the same snapshot as maps. Stdout only.

Text example (Backseat Driver `sessions` pretty-printed; other providers' custom keys print too):

```
calva-backseat-driver  ws-xf11vn  cursor  /Users/pez/Projects/backseat-driver/test-projects/example
  host: Pappas-data.local  age 3s
  mcp:  node ~/.config/calva/backseat-driver/calva-mcp-server.js <portFile> 127.0.0.1
  sessions:
    clj     .
    cljs    .  :app #8 "Chrome",  :tui #10 "Node"
```

First line: `serverName`, `windowId`, `appId`, `workspaceRoot` (absolute hashed location; text `no folder` when omitted). Session `projectRootDisplay` is relative to `workspaceFolder` (first folder), or the parent of a `.code-workspace` `workspaceRoot`.

Use the list to pick the relevant server. The listing contains `appId` (editor CLI slug), `workspaceRoot` (workspace file or folder; text `no folder` when empty), and other, provider-dependent information needed for discovery and connect.

Then attach: `node <wrapperPath> <portFilePath> <host>`, or write the client's MCP config from those fields. After attach, query MCP for live details. Shard discovery can lag; MCP is current.

If the agent cannot hold a normal MCP session, `bb mcp` is the no-session path: one JSON envelope on stdout. First command is `--readme` (server briefing); then `--readme-tool <name>`; then `resources/read` / `tools/call`. Recipe: installed `bb-mcp.md`. Copy `serverName` and `windowId` from `bb list`.

Stock MCP `tools/list` stays `name` / `description` (`modelDescription`) / `inputSchema`. An optional request arg `includeUserDescription` adds `userDescription` from package.json `languageModelTools`. Only `bb mcp --readme` sends that arg.

`watch-registry` (`bb watch-registry` in this repo) is a development event stream over the same shards. It stays in the vscode-mcp repo.

### Installed AGENTS.md

The installed `AGENTS.md` is the attach recipe. It says:

1. You were pointed at registry home.
2. Read `README.md` and `AGENTS.md`. This directory is overwritten. Put custom files in `~/.config/vscode-mcp`.
3. Prefer `bb list` (or `--edn` / `--json`).
4. If `bb` is missing, install Babashka and retry `bb list`. Prefer that over reading the JSON files.
5. Use the list to pick the relevant server. The listing contains `appId`, `workspaceRoot`, and other, provider-dependent information needed for discovery and connect.
6. Attach from `mcp`, or write client config from the same fields.
7. Query MCP for live details.
8. If you cannot hold a session, read `bb-mcp.md` and use `bb mcp`. First command is `--readme`.

Pez authors `README.md`. Pez authors `AGENTS.md` to match this recipe.

## Install of support files

Canonical sources in this repo:

```
assets/registry-content/
  README.md
  AGENTS.md
  bb-mcp.md
  bb.edn
  scripts/list_registry.clj
  scripts/mcp.clj
  scripts/mcp_briefing.clj
  fallback/README.md          embed-only; not installed as a directory
  fallback/AGENTS.md
```

No extra `create-config` key. Requires `:registry/enabled?`. Hook: first `registry-writer/on-started!+` in this Extension Host process. Fire-and-forget: socket start does not wait. Failures go to `:mcp/on-log`. Heartbeats and `update-registry!+` do not install.

Source order:

1. Debug (`goog.DEBUG`): `{extensionPath}/../vscode-mcp/assets/registry-content/` when that directory exists (sibling checkout).
2. Else GitHub raw, branch `master`: the seven consumer files from `https://raw.githubusercontent.com/BetterThanTomorrow/vscode-mcp/master/assets/registry-content/`.

The full listing tree is not shipped in the VSIX. Debug sibling install copies only the seven consumer files, not `fallback/`. Write when bytes differ. Grow `consumer-files` only after the new files exist; add the `mcp` task in the same batch as `scripts/mcp.clj`.

A process-local flag records a **successful** fetch for this session. Stop/start MCP in the same window does not fetch again after success.

Twenty Extension Hosts may each fire once. One write wins; the rest see matching bytes.

### First GitHub miss

Ongoing offline use is out of scope.

When GitHub fails and a previous successful install is present (`bb.edn` or the real README): leave the destination unchanged.

When GitHub fails and nothing usable is on disk yet: write embedded stub `README.md` and `AGENTS.md` at registry home (not inside `windows/`). Those two files are pez-authored in `assets/registry-content/fallback/` and embedded in the compiled library. The stub README says GitHub was unreachable, retry when it is available, and point the agent at `windows/` in the meantime. The stub `AGENTS.md` tells the agent to read the shards and attach from `mcp`.

A failed fetch does not set the success flag, so the next `on-started!+` retries GitHub. A later successful fetch replaces the stubs.

## Consumer skill pointer

When a user asks how to connect an external harness to this workstation's MCP: registry home is `~/.config/vscode-mcp/registry`. Point the agent at that directory. Keep the extension skill short; installed `AGENTS.md` owns session attach; `bb-mcp.md` owns `bb mcp`.
