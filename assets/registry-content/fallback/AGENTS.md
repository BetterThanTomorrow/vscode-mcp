# vscode-mcp registry home (listing files not installed yet)

GitHub was unreachable, so `bb.edn` and `bb list` are not here yet. The next MCP start will try GitHub again.

Until then, read `windows/*.json` in this directory. Use those records to pick a server (`appId`, `workspaceRoot` — a workspace file or folder, omitted when empty — and other, provider-dependent fields). Attach from `mcp`: spawn `node <wrapperPath> <portFilePath> <host>`, or write the harness' MCP config from the same fields and then ask the user to connect.

When the harness runs inside a VS Code window, or in one of its terminals, write the config with only the wrapper: `node <wrapperPath>`, with no port file. The wrapper then connects to the window the harness runs in, so one config works in every window, and it belongs in the harness' user-level config rather than a project's. Use `<portFilePath> <host>` only to reach a window other than your own. Write `wrapperPath` as the absolute path in the record; a JSON config doesn't expand `~`.

This directory is owned by vscode-mcp and will be overwritten. Put custom files in `~/.config/vscode-mcp`.
