(ns vscode-mcp.stdio-config
  "Internal — shared by `vscode-mcp.manual-setup`, `vscode-mcp.cursor-config`,
   `vscode-mcp.lifecycle.state`, and `vscode-mcp.stdio.wrapper`. Not part of
   the consumer API; no consumer needs to require this namespace directly."
  (:require
   [clojure.string :as string]))

(def default-host
  "127.0.0.1")

(defn normalize-host
  "Returns host when non-blank, otherwise default-host."
  [host]
  (if (string/blank? (str host))
    default-host
    (str host)))

(defn stdio-args
  "Argv vector for the stdio wrapper (excludes node)."
  [wrapper-path port-or-port-file-path host]
  [(str wrapper-path)
   (str port-or-port-file-path)
   (normalize-host host)])

(defn stdio-command-string
  "Full shell command string for manual MCP setup."
  [node-command wrapper-path port-or-port-file-path host]
  (string/join " " (cons (str node-command) (stdio-args wrapper-path port-or-port-file-path host))))
