(ns vscode-mcp.manual-setup.dialog
  "VS Code effects for manual MCP setup: the information dialog with copy
   buttons, and the manual-stop message. Kept apart from
   `vscode-mcp.manual-setup` so that namespace's pure logic can be unit
   tested without loading \"vscode\".

   Internal — both functions here are used only by `vscode-mcp.lifecycle`,
   which is the namespace consumers should require for the full manual-start
   flow. Not part of the consumer API."
  (:require
   ["vscode" :as vscode]
   [promesa.core :as p]
   [vscode-mcp.manual-setup :as manual-setup]))

(defn show-manual-start-dialog!+
  "Shows the manual-start information message with copy buttons, and writes
   the chosen command to the clipboard."
  ([wrapper-path server-info] (show-manual-start-dialog!+ wrapper-path server-info nil))
  ([wrapper-path server-info opts]
   (let [message (manual-setup/format-start-message server-info opts)
         {:manual-setup/keys [port port-file]} (manual-setup/copy-command-strings wrapper-path server-info)]
     (p/let [button (vscode/window.showInformationMessage
                     message
                     "Copy command + port"
                     "Copy command + port-file")]
       (case button
         "Copy command + port"
         (vscode/env.clipboard.writeText port)

         "Copy command + port-file"
         (vscode/env.clipboard.writeText port-file)

         nil)))))

(defn show-stopped-message!+
  "Shows \"MCP server stopped\" unless {:manual-setup/silent? true}."
  ([] (show-stopped-message!+ nil))
  ([{:manual-setup/keys [silent?]}]
   (if silent?
     (p/resolved nil)
     (vscode/window.showInformationMessage "MCP server stopped"))))
