# vscode-mcp Registry (listing files not installed yet)

GitHub was unreachable, so the listing files (`bb list`, the usual README, AGENTS.md) are not here yet.

Retry when GitHub is available. The next MCP server start will try again.

In the meantime, point the agent at `windows/` in this directory. Those JSON files have discovery and attach fields.

vscode-mcp owns this directory and will overwrite files here. Put custom files in the parent directory, `~/.config/vscode-mcp`.
