(ns vscode-mcp.jsonc
  "Classpath-vendored microsoft/jsonc-parser helpers.

  Bundle lives at `/vscode_mcp/vendor/jsonc_parser` (see `bb vendor-jsonc-parser`)."
  (:require
   ["/vscode_mcp/vendor/jsonc_parser" :as jsonc]))

(def ^:private default-modify-opts
  #js {:formattingOptions #js {:tabSize 2
                               :insertSpaces true}})

(defn parse
  "Parse JSONC `text` to a JS value."
  [text]
  (jsonc/parse text))

(defn parse-clj
  "Parse JSONC `text` to Clojure data with keywordized keys."
  [text]
  (js->clj (parse text) :keywordize-keys true))

(defn modify
  "Compute JSONC edits setting `path` to `value` (CLJ data).

  `path` is a sequence of string keys / int indices, e.g.
  `[\"mcpServers\" \"joyride\"]`."
  [text path value]
  (jsonc/modify text (clj->js path) (clj->js value) default-modify-opts))

(defn apply-edits
  "Apply `edits` from `modify` to `text`."
  [text edits]
  (jsonc/applyEdits text edits))

(defn assoc-in-text
  "Return JSONC text with `path` set to `value`, preserving comments.

  Blank/nil `text` is treated as `{}`."
  [text path value]
  (let [text (if (seq text) text "{}")]
    (apply-edits text (modify text path value))))
