(ns vscode-mcp.eca-config
  "Pure merge/compare for project-local `.eca/config.json` mcpServers entries."
  (:require
   ["path" :as path]
   [clojure.string :as string]
   [vscode-mcp.jsonc :as jsonc]
   [vscode-mcp.stdio-config :as stdio-config]))

(defn as-posix
  "Normalize path separators to forward slashes."
  [p]
  (string/replace p #"\\" "/"))

(defn- path-module
  ^js [platform]
  (if (= "win32" platform) path/win32 path/posix))

(defn- js-escape
  [character]
  (str "\\u" (.padStart (.toString (.charCodeAt character 0) 16) 4 "0")))

(defn- js-string-literal
  "Returns `s` as a single-quoted JS string literal, with every character but letters,
   digits and `._/:-` written as a `\\uXXXX` escape."
  [s]
  (str "'" (string/replace s #"[^A-Za-z0-9._/:-]" js-escape) "'"))

(defn- home-segments
  "Returns the path segments of `wrapper-path` below `home-dir`, or nil when it is outside home."
  [^js path-api home-dir wrapper-path]
  (let [relative (.relative path-api home-dir wrapper-path)
        segments (vec (.split relative (.-sep path-api)))]
    (when-not (or (string/blank? relative)
                  (.isAbsolute path-api relative)
                  (= ".." (first segments)))
      segments)))

(defn wrapper-require-script
  "Returns the `node -e` script that loads the wrapper at `wrapper-path`. A wrapper under
   `home-dir` is joined under `os.homedir()` at start, any other by its absolute path.
   `platform` is a `process.platform` value. The script holds no `$`, `\"`, space or tab."
  [{:os/keys [home-dir platform]
    wrapper-path :wrapper/path}]
  (let [path-api (path-module platform)]
    (if-let [segments (home-segments path-api home-dir wrapper-path)]
      (str "require(require('path').join(require('os').homedir(),"
           (string/join "," (map js-string-literal segments))
           "))")
      (str "require("
           (js-string-literal (cond-> wrapper-path
                                (= "win32" platform) as-posix))
           ")"))))

(def managed-fields
  ["command" "args"])

(defn desired-entry
  "Managed stdio MCP entry for ECA (`command` + `args` only). Node loads the wrapper
   through `wrapper-require-script`, and with the window registry off, the args end
   with `port-file-path` and `host`."
  [{:os/keys [platform]
    :registry/keys [enabled?]
    :server/keys [port-file-path host]
    wrapper-path :wrapper/path
    :as entry-opts}]
  {"command" "node"
   "args" (cond-> ["-e"
                   (wrapper-require-script entry-opts)
                   (.basename (path-module platform) wrapper-path)]
            (not enabled?) (conj (str port-file-path)
                                 (stdio-config/normalize-host host)))})

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
