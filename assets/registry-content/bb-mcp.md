# `bb mcp`

An MCP client of sort. Use when a harness MCP connector can't reach the VS Code machine.

From the registry home directory run one command against a live window and parse one JSON object on stdout. Copy `serverName` and `windowId` from `bb list`. (For discovery, the list header also shows `appId` and the hashed location.)

First command is `--readme`. Then `--readme-tool` for one tool. Then work verbs: `resources/read`, `tools/call`.

```sh
bb mcp --readme --server-name calva-backseat-driver --window-id <windowId>
bb mcp --hreadme --server-name calva-backseat-driver --window-id <windowId>
bb mcp --readme-tool clojure_evaluate_code --server-name calva-backseat-driver --window-id <windowId>
```

`--readme`, `--hreadme`, and `--readme-tool` are flags on `bb mcp`. They cannot be combined with each other or with a method verb. `--server-name` / `--window-id` are still required.

`--readme` briefs the server in one `result`: `serverInfo`, `instructions`, `description`, published skills (name, URI, description — not bodies), and tools as `name` + `userDescription`. Then read relevant skills with `resources/read`, and inspect a tool with `--readme-tool`.

`--readme-tool` result is `name`, `description`, `inputSchema`. Unknown name is `unknown-tool`. Next is `tools/call`.

Bare verbs stay MCP methods: `tools/list`, `tools/call`, `resources/list`, `resources/templates/list`, `resources/read`, `ping`.

```sh
bb mcp tools/list --server-name calva-backseat-driver --window-id <windowId>
bb mcp resources/list --server-name calva-backseat-driver --window-id <windowId>
bb mcp resources/templates/list --server-name calva-backseat-driver --window-id <windowId>
bb mcp ping --server-name calva-backseat-driver --window-id <windowId>
```

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
| `--readme` | first job | Server briefing (skills + tool names) |
| `--readme-tool` | first job | One tool's `name`, `description`, `inputSchema` |
| `--name` | `tools/call` | Tool name |
| `--uri` | `resources/read` | Resource URI |
| `--args -` | `tools/call` | JSON object on stdin. Omit `--args` to send `{}`. |
| `--timeout` | all | Seconds to wait for the JSON-RPC **response** after connect. Default **180**. `0` means wait until a line arrives. Does not cancel the tool. |
| `--hhelp` | help | Same CLI usage as `--help`, plain text on stdout (exit 0) |
| `--hreadme` | first job (text) | Same briefing as `--readme`, plain text on stdout |
| `--hreadme-tool` | help | Prose for what `--readme-tool` does |

Connect has its own 5 second budget. Connect failure is `window-gone`, not `timeout`.

Agents use `--help` / `-h`: JSON `invalid-args` envelope, exit 1, usage in `error.message`. Humans use `--hhelp` for usage (plain text, exit 0, no shard flags). `--hreadme` needs `--server-name` / `--window-id` and prints the live briefing as plain text (exit 0). `--hreadme-tool` is prose help for `--readme-tool` (plain text, exit 0, no shard flags).

## Envelope

Success:

```json
{"ok": true, "result": …}
```

`result` is that command's payload. For a bare verb, it is what that MCP method returned. For `--readme` / `--readme-tool`, it is the briefing above. A completed `tools/call` whose text is an error, or that sets `isError`, is still `ok: true` — read `result`.

Failure (nonzero exit):

```json
{"ok": false, "error": {"code": "timeout", "message": "…"}}
```

| `code` | When |
| --- | --- |
| `invalid-args` | Missing/unknown method, missing flags, `--readme` combined with a verb or `--readme-tool`, `--args -` without a JSON object on stdin |
| `unknown-id` | No shard with that `serverName` + `windowId` |
| `window-gone` | Shard exists but is not live, has no MCP address, or TCP connect fails |
| `unknown-tool` | `--readme-tool` name not in `tools/list`, or JSON-RPC `-32601` on `tools/call` |
| `unknown-resource` | JSON-RPC `-32602` on `resources/read` |
| `timeout` | `--timeout` exceeded and no complete JSON line arrived |
| `protocol-error` | Malformed JSON from the socket, parse error, or other unmatched RPC failure |
| `tool-error` | JSON-RPC `-32603` from a thrown handler |

`unknown-id` and `window-gone` both mean: run `bb list` again.

If the server returned a JSON-RPC `error`, that object is copied onto `error.rpc`.

## Image and audio paths

Image, audio, and resource `blob` parts are written under `../mcp-media/<serverName>-<windowId>/` (sibling of this registry home). In `result` those parts keep `type` and `mimeType` and gain `path`. Text is left as the server sent it.

Those files can vanish after about 10 minutes (the Extension Host sweeps by mtime). Read them in the same batch as the call that wrote them.
