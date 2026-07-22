(ns vscode-mcp.eca-config
  "Pure merge/compare for project-local `.eca/config.json` mcpServers entries."
  (:require
   [vscode-mcp.jsonc :as jsonc]
   [vscode-mcp.stdio-config :as stdio-config]))

(def managed-fields
  ["command" "args"])

(defn desired-entry
  "Managed stdio MCP entry for ECA (`command` + `args` only)."
  [wrapper-path port-file-path host]
  {"command" "node"
   "args" (stdio-config/stdio-args wrapper-path port-file-path host)})

(defn managed-equal?
  "True when managed fields of `existing` match `desired` (string keys)."
  [existing desired]
  (= (select-keys (if (map? existing) existing {}) managed-fields)
     (select-keys desired managed-fields)))

(defn merge-entry
  "Merge `desired` managed fields over `existing`, preserving siblings."
  [existing desired]
  (merge (if (map? existing) existing {}) desired))

(defn plan-config-text
  "Plan the next `.eca/config.json` text for `server-name`.

   Returns `{:eca/action :no-op|:write :eca/text ...}`. Blank/nil text is
   treated as `{}` for planning; `:no-op` returns the original text unchanged."
  [text server-name desired]
  (let [original (or text "")
        source (if (seq text) text "{}")
        data (js->clj (jsonc/parse source))
        existing (get-in data ["mcpServers" server-name])]
    (if (managed-equal? existing desired)
      {:eca/action :no-op
       :eca/text original}
      {:eca/action :write
       :eca/text (jsonc/assoc-in-text source
                                      ["mcpServers" server-name]
                                      (merge-entry existing desired))})))
