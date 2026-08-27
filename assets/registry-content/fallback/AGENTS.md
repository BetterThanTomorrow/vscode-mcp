# vscode-mcp registry home (listing files not installed yet)

GitHub was unreachable, so `bb.edn` and `bb list` are not here yet. The next MCP start will try GitHub again.

Until then, read `windows/*.json` in this directory. Use those records to pick a server (`workspaceRoot` and other, provider-dependent fields). Attach from `mcp`: spawn `node <wrapperPath> <portFilePath> <host>`, or write the harness' MCP config from the same fields and then ask the user to connect.

This directory is owned by vscode-mcp and will be overwritten. Put custom files in `~/.config/vscode-mcp`.
