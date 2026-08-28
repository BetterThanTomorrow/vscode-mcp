# vscode-mcp Registry

vscode-mcp provides VS Code extensions with services for hosting per VS Code window MCP servers. It keeps this registry to provide external AI harnesses (and humans) with a convenient way to discover and connect to the relevant server.

The **registry home** is `~/.config/vscode-mcp/registry`, consisting of:
* One entry per VS Code window, as `.json` files in `./windows`. The files have info about the server to help consumers discover relevant servers, plus information about how to attach. The discovery info will vary between the providers of the MCP server. (E.g. A Clojure REPL MCP server will contain info about active REPL sessions.)
* This README.md
* [AGENTS.md](AGENTS.md)
* A [Babashka](https://babashka.org) task, `bb list`, for listing the registry. It will list the information needed for discovering MCP servers and for attaching them.
* `bb mcp` (see [bb-mcp.md](bb-mcp.md)), an MCP client of sort. Use when an MCP client can't be setup (e.g. the connector can't reach the machine running VS Code). Start with `--readme`.

The easiest way to use the registry is probably to point your AI agent to the **registry home** and ask it to connect itself (or, if it can't do that fully, to create the necessary configuration so that you can connect the agent).

## Registry Home is Owned by vscode-mcp

Everything in the registry home is maintained and owned by vscode-mcp. The content can and will be overwritten. To customize your usage, one way is to use the parent directory, `~/.config/vscode-mcp`, for that.