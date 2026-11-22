# vscode-mcp registry home

This directory is **registry home**. vscode-mcp owns it and will overwrite files here. Put custom files in the parent directory, `~/.config/vscode-mcp`, not here.

Read `README.md` as well as this file, and keep both in mind while you work.

## List servers

Prefer `bb list` over reading `windows/*.json` yourself.

```sh
bb list           # live windows, text
bb list --all     # every live window, also from inside a window
bb list --edn     # same snapshot as EDN
bb list --json    # same snapshot as JSON
bb list --stale   # include entries that fail the live filter (debugging)
```

Inside a VS Code window, or in one of its terminals, `bb list` lists the window you run in, and says how many other live windows there are. `bb list --all` lists every window. Outside every window, `bb list` lists every live window.

If `bb` is not on `PATH`, install Babashka from https://github.com/babashka/babashka#installation and retry `bb list`. Prefer that over opening the JSON files. Reading `windows/*.json` is valid (those files are what the lister parses); it is the worse path when the lister is installed.

## Pick a server and attach

Use the list to pick the relevant server. The listing contains `appId` (editor CLI slug), `workspaceRoot` (workspace file or folder; text `no folder` when empty), and other, provider-dependent information needed for discovery and connect.

Attach with the `mcp:` line's command, or the `mcp` fields in `--json`: spawn it, or write the harness' MCP config from it and then ask the user to connect. In the caller's window the command is `node <wrapperPath>`, and `mcp` holds only `wrapperPath`. Listing other windows, the command is `node <wrapperPath> <portFilePath> <host>`, and `mcp` has those fields and the port.

When the harness runs inside a VS Code window, or in one of its terminals, write the config with only the wrapper: `node <wrapperPath>`, with no port file. The wrapper then connects to the window the harness runs in, so one config works in every window, and it belongs in the harness' user-level config rather than a project's. Use `<portFilePath> <host>` only to reach a window other than your own.

After attach, query MCP for live details. Entry session and runtime fields can lag; MCP is current.

## If you cannot hold an MCP session

Read `bb-mcp.md` and use `bb mcp`. First command is `--readme`. Copy `serverName` from `bb list`. Inside a VS Code window, leave out `--window-id` to reach the window you run in. To reach another window, or from outside every window, copy its `windowId` from `bb list --all`.
