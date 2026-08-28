# vscode-mcp registry home

This directory is **registry home**. vscode-mcp owns it and will overwrite files here. Put custom files in the parent directory, `~/.config/vscode-mcp`, not here.

Read `README.md` as well as this file, and keep both in mind while you work.

## List servers

Prefer `bb list` over reading `windows/*.json` yourself.

```sh
bb list           # live windows, text
bb list --edn     # same snapshot as EDN
bb list --json    # same snapshot as JSON
bb list --stale   # include shards that fail the live filter (debugging)
```

If `bb` is not on `PATH`, install Babashka from https://github.com/babashka/babashka#installation and retry `bb list`. Prefer that over opening the JSON files. Reading `windows/*.json` is valid (those files are what the lister parses); it is the worse path when the lister is installed.

## Pick a server and attach

Use the list to pick the relevant server. The listing contains `appId` (editor CLI slug), `workspaceRoot` (workspace file or folder; text `no folder` when empty), and other, provider-dependent information needed for discovery and connect.

Attach from the `mcp` fields: spawn `node <wrapperPath> <portFilePath> <host>`, or write the harness' MCP config from the same fields and then ask the user to connect.

After attach, query MCP for live details. Shard session and runtime fields can lag; MCP is current.

## If you cannot hold an MCP session

Read `bb-mcp.md` and use `bb mcp`. First command is `--readme`. Copy `serverName` and `windowId` from `bb list`.
