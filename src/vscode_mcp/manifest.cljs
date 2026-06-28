(ns vscode-mcp.manifest
  (:require
   ["fs" :as fs]
   ["path" :as path]))

(defn satisfies-when? [when-clause settings]
  (if (or (nil? when-clause) (empty? when-clause))
    true
    ;; If the when clause matches a key in settings, use its boolean value.
    ;; Otherwise, we assume it's true to be safe, or we could assume false if settings are strict.
    ;; For now, if settings explicitly contains the key, we use it. Otherwise true.
    (get settings when-clause true)))

(defn read-skill-frontmatter [content]
  (when-let [[_ frontmatter] (re-find #"(?s)^---\n(.*?)\n---" content)]
    (let [desc-match (re-find #"(?m)^description:\s*['\"]?(.*?)['\"]?\s*$" frontmatter)
          name-match (re-find #"(?m)^name:\s*['\"]?(.*?)['\"]?\s*$" frontmatter)]
      {:description (when desc-match (second desc-match))
       :name (when name-match (second name-match))})))

(defn get-tools
  "Reads `contributes.languageModelTools` from the extension's package.json
   and returns them in the format expected by MCP `tools/list`.
   `settings` is an optional map of {when-clause boolean} to filter tools."
  [^js context & [{:keys [settings] :or {settings {}}}]]
  (try
    (let [^js package-json (-> context .-extension .-packageJSON)
          tools (some-> package-json .-contributes .-languageModelTools)]
      (if-not tools
        []
        (->> tools
             (filter (fn [^js tool]
                       (satisfies-when? (.-when tool) settings)))
             (map (fn [^js tool]
                    (let [schema (js->clj (.-inputSchema tool) :keywordize-keys true)]
                      {:name (.-name tool)
                       :description (.-modelDescription tool)
                       :inputSchema (select-keys schema [:type :properties :required])})))
             vec)))
    (catch js/Error e
      (js/console.error "[MCP Manifest] Error getting tools:" (.-message e))
      [])))

(defn get-resources
  "Reads `contributes.chatSkills` from the extension's package.json,
   reads their frontmatter to extract `name` and `description`,
   and returns them in the format expected by MCP `resources/list`.
   `settings` is an optional map of {when-clause boolean} to filter skills."
  [^js context & [{:keys [settings] :or {settings {}}}]]
  (try
    (let [^js package-json (-> context .-extension .-packageJSON)
          skills (some-> package-json .-contributes .-chatSkills)
          extension-path (.-extensionPath context)]
      (if-not skills
        []
        (->> skills
             (filter (fn [^js skill]
                       (satisfies-when? (.-when skill) settings)))
             (keep (fn [^js skill]
                     (let [skill-path (.-path skill)
                           abs-path (path/join extension-path skill-path)]
                       (try
                         (let [content (fs/readFileSync abs-path "utf8")
                               frontmatter (read-skill-frontmatter content)
                               skill-name (or (:name frontmatter)
                                              ;; Fallback to directory name if name isn't in frontmatter
                                              (path/basename (path/dirname abs-path)))]
                           {:uri (str "skill://" skill-name)
                            :name skill-name
                            :description (or (:description frontmatter) "No description provided.")
                            :mimeType "text/markdown"
                            :skill-path abs-path}) ; Keep path for read-resource mapping
                         (catch js/Error e
                           (js/console.warn "[MCP Manifest] Could not read skill at" abs-path ":" (.-message e))
                           nil)))))
             vec)))
    (catch js/Error e
      (js/console.error "[MCP Manifest] Error getting resources:" (.-message e))
      [])))

(defn read-resource
  "Given a resource URI requested via MCP `resources/read`, looks up the resource
   and returns its content. Returns nil if not found."
  [^js context uri & [options]]
  (let [resources (get-resources context options)]
    (when-let [resource (first (filter #(= (:uri %) uri) resources))]
      (try
        {:uri (:uri resource)
         :mimeType (:mimeType resource)
         :text (fs/readFileSync (:skill-path resource) "utf8")}
        (catch js/Error e
          (js/console.error "[MCP Manifest] Error reading resource" uri ":" (.-message e))
          nil)))))
