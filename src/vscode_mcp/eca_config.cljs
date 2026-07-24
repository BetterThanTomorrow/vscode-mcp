(ns vscode-mcp.eca-config
  "Pure merge/compare for project-local `.eca/config.json` mcpServers entries."
  (:require
   [clojure.string :as string]
   [vscode-mcp.jsonc :as jsonc]
   [vscode-mcp.stdio-config :as stdio-config]))

(defn as-posix
  "Normalize path separators to forward slashes."
  [p]
  (string/replace p #"\\" "/"))

(defn under-dir?
  "True when `abs-path` is `dir` or a descendant (posix string compare)."
  [abs-path dir]
  (let [abs (as-posix abs-path)
        dir (-> dir as-posix (string/replace #"/+$" ""))]
    (or (= abs dir)
        (string/starts-with? abs (str dir "/")))))

(defn home-env-path
  "When `abs-path` is under `home-dir`, return `${env:HOME}/` + relative-suffix; else absolute posix."
  [abs-path home-dir]
  (let [abs (as-posix abs-path)
        home (-> home-dir as-posix (string/replace #"/+$" ""))]
    (if (under-dir? abs home)
      (str "${env:HOME}/" (subs abs (inc (count home))))
      abs)))

(defn workspace-relative-path
  "When `abs-path` is under `workspace-root`, return relative suffix; else absolute posix."
  [abs-path workspace-root]
  (let [abs (as-posix abs-path)
        root (-> workspace-root as-posix (string/replace #"/+$" ""))]
    (if (under-dir? abs root)
      (subs abs (inc (count root)))
      abs)))

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
