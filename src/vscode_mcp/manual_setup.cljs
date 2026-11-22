(ns vscode-mcp.manual-setup
  "Pure manual-start UX logic — no VS Code API touched here, so this
   namespace (and its tests) never need to load `\"vscode\"`.
   See `vscode-mcp.manual-setup.dialog` for the VS Code effects that use it.

   Consumer API: `copy-command-strings`, for building your own manual-setup
   UI without the built-in dialog.

   `format-start-message` is internal — used only by
   `vscode-mcp.manual-setup.dialog`."
  (:require
   [vscode-mcp.stdio-config :as stdio-config]))

(defn copy-command-strings
  "`node …` shell commands for the manual-start copy buttons. `:manual-setup/caller-window`
   names no window, so the wrapper connects to the window its client runs in. The others
   name this window, by port or port file, and its host.
   Returns {:manual-setup/caller-window … :manual-setup/port … :manual-setup/port-file …}."
  [wrapper-path server-info]
  (let [{:server/keys [assigned-port host ^js port-file-uri]} server-info
        port-file-path (some-> port-file-uri (unchecked-get "fsPath"))]
    {:manual-setup/caller-window (str "node " wrapper-path)
     :manual-setup/port (stdio-config/stdio-command-string "node" wrapper-path (str assigned-port) host)
     :manual-setup/port-file (stdio-config/stdio-command-string "node" wrapper-path port-file-path host)}))

(def two-ways-text
  "There are two ways to run the MCP server, one for agents situated in this VS Code window, and one for external agents. You can copy the command you need with the buttons below.")

(defn format-start-message
  "Manual-start message: optional port-note prefix, assigned port, the two ways to
   run the server, and an optional product-specific suffix (e.g. a BD README blurb)."
  ([server-info] (format-start-message server-info nil))
  ([server-info {:manual-setup/keys [message-suffix extension-name]}]
   (let [{:server/keys [assigned-port port-note server-name]} server-info
         base (str extension-name ": " port-note " MCP socket server started on port: " assigned-port
                   ". " two-ways-text)]
     (if (seq message-suffix)
       (str base "\n\n" message-suffix)
       base))))
