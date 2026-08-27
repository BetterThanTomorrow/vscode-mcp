# `bb mcp`

From this directory, after `bb list`, run one MCP method against a live window and parse one JSON object on stdout. Copy `serverName` and `windowId` from `bb list`.

```sh
bb mcp initialize --server-name calva-backseat-driver --window-id <windowId>
bb mcp tools/list --server-name calva-backseat-driver --window-id <windowId>
bb mcp resources/list --server-name calva-backseat-driver --window-id <windowId>
bb mcp resources/templates/list --server-name calva-backseat-driver --window-id <windowId>
bb mcp ping --server-name calva-backseat-driver --window-id <windowId>
```

`initialize` is how you learn the server (`serverInfo`, `instructions`, `description`). Tool catalogs are `tools/list`. Skills are `resources/list` and `skill://index.json` (read with `resources/read`).

## Tools and resources

Empty-args tool (omit `--args`):

```sh
bb mcp tools/call --server-name calva-backseat-driver --window-id <windowId> \
  --name clojure_list_sessions
```

Nested arguments as JSON on stdin (`--args -`):

```sh
bb mcp tools/call --server-name calva-backseat-driver --window-id <windowId> \
  --name clojure_evaluate_code --args - <<'EOF'
{"code":"(+ 1 2)","namespace":"user","replSessionKey":"clj","who":"agent"}
EOF
```

Read a skill:

```sh
bb mcp resources/read --server-name calva-backseat-driver --window-id <windowId> \
  --uri skill://backseat-driver/SKILL.md
```

| Flag | Used by | Meaning |
| --- | --- | --- |
| `--server-name` | all | Shard `serverName` |
| `--window-id` | all | Shard `windowId` |
| `--name` | `tools/call` | Tool name |
| `--uri` | `resources/read` | Resource URI |
| `--args -` | `tools/call` | JSON object on stdin. Omit `--args` to send `{}`. |
| `--timeout` | all | Seconds to wait for the JSON-RPC **response** after connect. Default **180**. `0` means wait until a line arrives. Does not cancel the tool. |

Connect has its own 5 second budget. Connect failure is `window-gone`, not `timeout`.

`--help` / `-h` prints the same failure envelope as other flag errors (`invalid-args`, exit 1), with usage in `error.message`.

## Envelope

Success:

```json
{"ok": true, "result": …}
```

`result` is what that MCP method returned. A completed `tools/call` whose text is an error, or that sets `isError`, is still `ok: true` — read `result`.

Failure (nonzero exit):

```json
{"ok": false, "error": {"code": "timeout", "message": "…"}}
```

| `code` | When |
| --- | --- |
| `invalid-args` | Missing/unknown method, missing flags, `--args -` without a JSON object on stdin |
| `unknown-id` | No shard with that `serverName` + `windowId` |
| `window-gone` | Shard exists but is not live, has no MCP address, or TCP connect fails |
| `unknown-tool` | JSON-RPC `-32601` on `tools/call` |
| `unknown-resource` | JSON-RPC `-32602` on `resources/read` |
| `timeout` | `--timeout` exceeded and no complete JSON line arrived |
| `protocol-error` | Malformed JSON from the socket, parse error, or other unmatched RPC failure |
| `tool-error` | JSON-RPC `-32603` from a thrown handler |

`unknown-id` and `window-gone` both mean: run `bb list` again.

If the server returned a JSON-RPC `error`, that object is copied onto `error.rpc`.

## Image and audio paths

Image, audio, and resource `blob` parts are written under `../mcp-media/<serverName>-<windowId>/` (sibling of this registry home). In `result` those parts keep `type` and `mimeType` and gain `path`. Text is left as the server sent it.

Those files can vanish after about 10 minutes (the Extension Host sweeps by mtime). Read them in the same batch as the call that wrote them.
