(ns vscode-mcp.jsonc
  "Classpath-vendored microsoft/jsonc-parser helpers.

  Bundle lives at `/vscode_mcp/vendor/jsonc_parser` (see `bb vendor-jsonc-parser`)."
  (:require
   ["/vscode_mcp/vendor/jsonc_parser" :as jsonc]
   [clojure.string :as string]))

(defn- infer-formatting-options
  "Infer FormattingOptions from existing JSONC `text`.

  Existing space-indented files: tabSize is the smallest positive leading-space
  indent. Tab-indented files: insertSpaces false. Blank / no indented lines:
  tabSize 2, insertSpaces true (create-from-scratch default)."
  [text]
  (let [lines (string/split-lines (str (or text "")))
        space-widths (into []
                           (keep (fn [line]
                                   (when-let [[_ ws] (re-matches #"^( +)\S.*" line)]
                                     (count ws))))
                           lines)
        tab-indented? (boolean (some #(re-matches #"^\t+\S.*" %) lines))]
    (cond
      tab-indented?
      #js {:tabSize 2 :insertSpaces false}

      (seq space-widths)
      #js {:tabSize (apply min space-widths) :insertSpaces true}

      :else
      #js {:tabSize 2 :insertSpaces true})))

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
  `[\"mcpServers\" \"joyride\"]`. FormattingOptions follow `text` indent
  (see `infer-formatting-options`)."
  [text path value]
  (jsonc/modify text
                (clj->js path)
                (clj->js value)
                #js {:formattingOptions (infer-formatting-options text)}))

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
