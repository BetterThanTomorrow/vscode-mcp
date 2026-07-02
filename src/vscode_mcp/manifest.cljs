(ns vscode-mcp.manifest
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [clojure.string :as string]))

(defn- oget
  "String-keyed property access on external JS data — immune to Closure
   property renaming in advanced compilation (unlike `.-prop` access, which
   needs externs or successful inference)."
  [o k]
  (when o (unchecked-get o k)))

(defn- oget-in [o ks]
  (reduce oget o ks))

(defn- manifest-tools [context]
  (oget-in context ["extension" "packageJSON" "contributes" "languageModelTools"]))

(defn- manifest-skills [context]
  (oget-in context ["extension" "packageJSON" "contributes" "chatSkills"]))

(defn- satisfies-when? [when-clause settings]
  (if (or (nil? when-clause) (empty? when-clause))
    true
    ;; If the when clause matches a key in settings, use its boolean value.
    ;; Otherwise, we assume it's true to be safe, or we could assume false if settings are strict.
    ;; For now, if settings explicitly contains the key, we use it. Otherwise true.
    (get settings when-clause true)))

(defn- find-tool-by-name [tools tool-name]
  (when tools
    (some #(when (= (oget % "name") tool-name) %) tools)))

(defn tool-call-allowed?
  "Returns :allowed, :disabled, or :unknown for a tool name against manifest when clauses."
  [context tool-name & [{:keys [settings] :or {settings {}}}]]
  (let [tools (manifest-tools context)
        tool (find-tool-by-name tools tool-name)]
    (cond
      (nil? tool) :unknown
      (satisfies-when? (oget tool "when") settings) :allowed
      :else :disabled)))

(defn- clean-yaml-value [v]
  (-> v
      string/trim
      (string/replace #"^[>|]-?[ \t]*(?:\r\n|\n|\r)[ \t]*" "")))

(defn- parse-frontmatter
  "Parses frontmatter text (lines between --- delimiters) into a map.
   Handles multi-line values by concatenating continuation lines.
   Keys are converted to keywords, values are kept as raw strings but trimmed overall."
  [frontmatter-text]
  (let [lines (string/split frontmatter-text #"\r\n|\n|\r")
        {:keys [acc current-key current-value]}
        (reduce (fn [{:keys [acc current-key current-value] :as state} line]
                  (if-let [key-match (re-find #"^([a-zA-Z0-9_-]+):[ \t]*(.*)$" line)]
                    (let [new-key (keyword (string/trim (second key-match)))
                          new-value (string/trim (nth key-match 2))
                          new-acc (if current-key
                                    (assoc acc current-key (clean-yaml-value current-value))
                                    acc)]
                      {:acc new-acc
                       :current-key new-key
                       :current-value new-value})
                    (if current-key
                      {:acc acc
                       :current-key current-key
                       :current-value (str current-value "\n" line)}
                      state)))
                {:acc {}
                 :current-key nil
                 :current-value ""}
                lines)]
    (if current-key
      (assoc acc current-key (clean-yaml-value current-value))
      acc)))

(defn- read-skill-frontmatter [content]
  (when-let [[_ frontmatter-text] (re-find #"^---\s*\n([\s\S]*?)\n---" content)]
    (let [parsed (parse-frontmatter frontmatter-text)]
      {:description (:description parsed)
       :name (:name parsed)})))

(defn get-tools
  "Reads `contributes.languageModelTools` from the extension's package.json
   and returns them in the format expected by MCP `tools/list`.
   `settings` is an optional map of {when-clause boolean} to filter tools."
  [context & [{:keys [settings] :or {settings {}}}]]
  (try
    (let [tools (manifest-tools context)]
      (if-not tools
        []
        (->> tools
             (filter (fn [tool]
                       (satisfies-when? (oget tool "when") settings)))
             (map (fn [tool]
                    (let [schema (js->clj (oget tool "inputSchema") :keywordize-keys true)]
                      {:name (oget tool "name")
                       :description (oget tool "modelDescription")
                       :inputSchema (select-keys schema [:type :properties :required])})))
             vec)))
    (catch js/Error e
      (js/console.error "[MCP Manifest] Error getting tools:" (.-message e))
      [])))

(defn- read-skill-resource [extension-path skill]
  (let [skill-path (oget skill "path")
        abs-path (path/join extension-path skill-path)]
    (try
      (let [content (fs/readFileSync abs-path "utf8")
            frontmatter (read-skill-frontmatter content)
            skill-name (or (:name frontmatter)
                           (path/basename (path/dirname abs-path)))]
        {:uri (str "skill://" skill-name)
         :name skill-name
         :description (or (:description frontmatter) "No description provided.")
         :mimeType "text/markdown"
         :skill-path abs-path})
      (catch js/Error e
        (js/console.warn "[MCP Manifest] Could not read skill at" abs-path ":" (.-message e))
        nil))))

(defn get-resources
  "Reads `contributes.chatSkills` from the extension's package.json,
   reads their frontmatter to extract `name` and `description`,
   and returns them in the format expected by MCP `resources/list`.
   `settings` is an optional map of {when-clause boolean} to filter skills."
  [context & [{:keys [settings] :or {settings {}}}]]
  (try
    (let [skills (manifest-skills context)
          extension-path (oget context "extensionPath")]
      (if-not skills
        []
        (->> skills
             (filter (fn [skill]
                       (satisfies-when? (oget skill "when") settings)))
             (keep (fn [skill]
                     (read-skill-resource extension-path skill)))
             vec)))
    (catch js/Error e
      (js/console.error "[MCP Manifest] Error getting resources:" (.-message e))
      [])))

(defn find-skill-resource-by-uri [resources uri]
  (or (some #(when (= (:uri %) uri) %) resources)
      (when (and (string/starts-with? uri "skill://")
                 (string/ends-with? uri "/SKILL.md"))
        (let [canonical-uri (subs uri 0 (- (count uri) (count "/SKILL.md")))]
          (some #(when (= (:uri %) canonical-uri) %) resources)))))

(defn read-resource
  "Given a resource URI requested via MCP `resources/read`, looks up the resource
   and returns its content. Returns nil if not found."
  [^js context uri & [options]]
  (let [resources (get-resources context options)]
    (when-let [resource (find-skill-resource-by-uri resources uri)]
      (try
        {:uri uri
         :mimeType (:mimeType resource)
         :text (fs/readFileSync (:skill-path resource) "utf8")}
        (catch js/Error e
          (js/console.error "[MCP Manifest] Error reading resource" uri ":" (.-message e))
          nil)))))

(defn build-server-instructions
  "Generates an instructional string for MCP clients based on available tools and resources.
   Optional `:base-text` can be provided to prepend custom instructions."
  [{:keys [base-text tools resources]}]
  (let [tools-text (when (seq tools)
                     (str "Available tools:\n"
                          (string/join "\n" (map (fn [{:keys [name description]}]
                                                   (str "- **`" name "`**: " description))
                                                 tools))))
        resources-text (when (seq resources)
                         (str "Specialized skills are available as resources. Use `resources/list` to discover them and `resources/read` to load their full instructions before starting work in their domain:\n"
                              (string/join "\n" (map (fn [{:keys [name description]}]
                                                       (str "- **" name "**: " description))
                                                     resources))))]
    (let [parts (remove string/blank? [base-text tools-text resources-text])]
      (when (seq parts)
        (string/join "\n\n" parts)))))

(defn build-initialize-result
  "Generates an MCP `initialize` result map.
   Extracts `name` and `version` from the extension's package.json, which can be overridden via `opts`.
   Also gathers available tools, resources, and generates `serverUseInstructions`.
   `opts` may include:
   - `:name`: Override the server name.
   - `:version`: Override the server version.
   - `:base-text`: Prepend custom text to the generated server instructions.
   - `:settings`: Map of {when-clause boolean} to filter tools and resources."
  [context & [opts]]
  (let [package-json (oget-in context ["extension" "packageJSON"])
        server-name (or (:name opts) (oget package-json "name") "vscode-mcp-server")
        server-version (or (:version opts) (oget package-json "version") "0.0.0")
        settings (:settings opts)
        tools (get-tools context {:settings settings})
        resources (get-resources context {:settings settings})
        instructions (build-server-instructions {:base-text (:base-text opts)
                                                 :tools tools
                                                 :resources resources})]
    {:protocolVersion "2024-11-05"
     :capabilities {:tools {}
                    :resources {}}
     :instructions instructions
     :serverInfo {:name server-name
                  :version server-version}}))
