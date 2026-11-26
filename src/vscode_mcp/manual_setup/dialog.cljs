(ns vscode-mcp.manual-setup.dialog
  "VS Code effects for manual MCP setup: the information dialog with copy
   buttons, and the manual-stop message. Kept apart from
   `vscode-mcp.manual-setup` so that namespace's pure logic can be unit
   tested without loading \"vscode\".

   Internal — both functions here are used only by `vscode-mcp.core`,
   which is the namespace consumers should require for the full manual-start
   flow. Not part of the consumer API."
  (:require
   ["vscode" :as vscode]
   [vscode-mcp.manual-setup :as manual-setup]))

(defn ^:async show-manual-start-dialog!+
  "Shows the manual-start information message with copy buttons, and writes
   the chosen command to the clipboard."
  ([wrapper-path server-info] (await (show-manual-start-dialog!+ wrapper-path server-info nil)))
  ([wrapper-path server-info opts]
   (let [message (manual-setup/format-start-message server-info opts)
         {:manual-setup/keys [caller-window port-file]} (manual-setup/copy-command-strings wrapper-path server-info)
         button (await (vscode/window.showInformationMessage message "This window" "External"))]
     (case button
       "This window" (await (vscode/env.clipboard.writeText caller-window))
       "External" (await (vscode/env.clipboard.writeText port-file))
       nil))))

(defn show-stopped-message!+
  "Shows \"MCP server stopped\" unless {:manual-setup/silent? true}."
  ([] (show-stopped-message!+ nil))
  ([{:manual-setup/keys [silent? extension-name]}]
   (if silent?
     (js/Promise.resolve nil)
     (vscode/window.showInformationMessage (str extension-name ": MCP server stopped")))))
