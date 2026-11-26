(ns vscode-mcp.manifest
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [clojure.string :as string]
   [clojure.walk :as walk]))

(defn- oget
  "String-keyed property access on external JS data — immune to Closure
   property renaming in advanced compilation (unlike `.-prop` access, which
   needs externs or successful inference)."
  [o k]
  (when o (unchecked-get o k)))

(defn- oget-in [o ks]
  (reduce oget o ks))

(defn manifest-data
  "Returns the manifest of the extension that `context` is the extension context of, as
   data: its name and version, its tools and skills as they are in package.json with
   string keys, and the extension's path. The other functions here take this map as
   their `manifest`."
  [context]
  (let [package-json (oget-in context ["extension" "packageJSON"])]
    {:manifest/name (oget package-json "name")
     :manifest/version (oget package-json "version")
     :manifest/tools (js->clj (oget-in package-json ["contributes" "languageModelTools"]))
     :manifest/skills (js->clj (oget-in package-json ["contributes" "chatSkills"]))
     :manifest/extension-path (oget context "extensionPath")}))

(defn- satisfies-when? [when-clause settings]
  (if (or (nil? when-clause) (empty? when-clause))
    true
    ;; If the when clause matches a key in settings, use its boolean value.
    ;; Otherwise, we assume it's true to be safe, or we could assume false if settings are strict.
    ;; For now, if settings explicitly contains the key, we use it. Otherwise true.
    (get settings when-clause true)))

(defn- find-tool-by-name [tools tool-name]
  (when tools
    (some #(when (= (get % "name") tool-name) %) tools)))

(defn tool-call-allowed?
  "Returns :allowed, :disabled, or :unknown for a tool name against manifest when clauses."
  [manifest tool-name & [{:keys [settings] :or {settings {}}}]]
  (let [tools (:manifest/tools manifest)
        tool (find-tool-by-name tools tool-name)]
    (cond
      (nil? tool) :unknown
      (satisfies-when? (get tool "when") settings) :allowed
      :else :disabled)))

(defn- unquoted
  "Returns the YAML value `v` without the quotes around it, when it is quoted as a whole.
   In a single-quoted value `''` is a quote, and in a double-quoted one `\\\"` is a
   quote and `\\\\` a backslash."
  [v]
  (cond
    (re-matches #"'(?:[^']|'')*'" v)
    (string/replace (subs v 1 (dec (count v))) "''" "'")

    (re-matches #"\"(?:[^\"\\]|\\[\s\S])*\"" v)
    (string/replace (subs v 1 (dec (count v))) #"\\([\"\\])" "$1")

    :else v))

(defn- clean-yaml-value [v]
  (-> v
      string/trim
      (string/replace #"^[>|]-?[ \t]*(?:\r\n|\n|\r)[ \t]*" "")
      unquoted))

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

(defn- tool-list-entry
  [tool include-user-description?]
  (let [schema (walk/keywordize-keys (get tool "inputSchema"))
        entry {:name (get tool "name")
               :description (get tool "modelDescription")
               :inputSchema (select-keys schema [:type :properties :required])}]
    (cond-> entry
      include-user-description? (assoc :userDescription (get tool "userDescription")))))

(defn get-tools
  "Returns the `manifest`'s tools in the format expected by MCP `tools/list`.
   `settings` is an optional map of {when-clause boolean} to filter tools.
   `includeUserDescription` adds each tool's package.json `userDescription`."
  [manifest & [{:keys [settings includeUserDescription] :or {settings {}}}]]
  (try
    (let [tools (:manifest/tools manifest)]
      (if-not tools
        []
        (->> tools
             (filter (fn [tool]
                       (satisfies-when? (get tool "when") settings)))
             (map #(tool-list-entry % includeUserDescription))
             vec)))
    (catch js/Error e
      (js/console.error "[MCP Manifest] Error getting tools:" (.-message e))
      [])))

(defn canonical-skill-uri
  "Returns the SEP-shaped skill entry URI `skill://{name}/SKILL.md`."
  [skill-name]
  (str "skill://" skill-name "/SKILL.md"))

(def skills-index-uri "skill://index.json")

(def skills-index-schema "https://schemas.agentskills.io/discovery/0.2.0/schema.json")

(defn build-skills-index
  "Builds the SEP-2640 discovery index map from skill entry-point resources."
  [resources]
  {:$schema skills-index-schema
   :skills (mapv (fn [{:keys [name description uri]}]
                   {:name name
                    :type "skill-md"
                    :description description
                    :url uri})
                 resources)})

(defn- read-skill-resource [extension-path skill]
  (let [skill-path (get skill "path")
        abs-path (path/join extension-path skill-path)]
    (try
      (let [content (fs/readFileSync abs-path "utf8")
            frontmatter (read-skill-frontmatter content)
            skill-name (or (:name frontmatter)
                           (path/basename (path/dirname abs-path)))]
        {:uri (canonical-skill-uri skill-name)
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
  [manifest & [{:keys [settings] :or {settings {}}}]]
  (try
    (let [skills (:manifest/skills manifest)
          extension-path (:manifest/extension-path manifest)]
      (if-not skills
        []
        (->> skills
             (filter (fn [skill]
                       (satisfies-when? (get skill "when") settings)))
             (keep (fn [skill]
                     (read-skill-resource extension-path skill)))
             vec)))
    (catch js/Error e
      (js/console.error "[MCP Manifest] Error getting resources:" (.-message e))
      [])))

(defn- mime-type-for-path [file-path]
  (if (string/ends-with? file-path ".md")
    "text/markdown"
    "text/plain"))

(defn- unsafe-rel-path? [rel-path]
  (or (string/starts-with? rel-path "/")
      (boolean (some #{".."} (string/split rel-path #"/")))))

(defn- safe-skill-sibling-path [skill-root rel-path]
  (when (and rel-path (not (unsafe-rel-path? rel-path)))
    (let [resolved (path/resolve skill-root rel-path)]
      (when (and (string/starts-with? (str resolved path/sep)
                                      (str skill-root path/sep))
                 (fs/existsSync resolved)
                 (.isFile (fs/statSync resolved)))
        resolved))))

(defn- parse-skill-sibling-uri [uri]
  (when (and (string/starts-with? uri "skill://")
             (not= uri skills-index-uri))
    (let [rest-uri (subs uri (count "skill://"))
          slash-idx (string/index-of rest-uri "/")]
      (when slash-idx
        (let [skill-name (subs rest-uri 0 slash-idx)
              rel-path (subs rest-uri (inc slash-idx))]
          (when (and (not (string/blank? skill-name))
                     (not (string/blank? rel-path)))
            {:name skill-name :rel-path rel-path}))))))

(defn- read-skill-sibling-resource [resource uri rel-path]
  (let [skill-dir (path/dirname (:skill-path resource))]
    (when-let [file-path (safe-skill-sibling-path skill-dir rel-path)]
      (try
        {:uri uri
         :mimeType (mime-type-for-path file-path)
         :text (fs/readFileSync file-path "utf8")}
        (catch js/Error e
          (js/console.error "[MCP Manifest] Error reading skill sibling" uri ":" (.-message e))
          nil)))))

(defn- find-skill-by-name [resources name]
  (some #(when (= (:name %) name) %) resources))

(defn- read-skill-entry-resource [resource uri]
  (try
    {:uri uri
     :mimeType (:mimeType resource)
     :text (fs/readFileSync (:skill-path resource) "utf8")}
    (catch js/Error e
      (js/console.error "[MCP Manifest] Error reading resource" uri ":" (.-message e))
      nil)))

(defn find-skill-resource-by-uri [resources uri]
  (or (some #(when (= (:uri %) uri) %) resources)
      (when (and (string/starts-with? uri "skill://")
                 (not (string/includes? (subs uri (count "skill://")) "/")))
        (let [skill-name (subs uri (count "skill://"))
              canonical (canonical-skill-uri skill-name)]
          (some #(when (= (:uri %) canonical) %) resources)))))

(defn read-resource
  "Given a resource URI requested via MCP `resources/read`, looks up the resource
   and returns its content. Returns nil if not found."
  [manifest uri & [options]]
  (let [resources (get-resources manifest options)]
    (cond
      (= uri skills-index-uri)
      {:uri uri
       :mimeType "application/json"
       :text (js/JSON.stringify (clj->js (build-skills-index resources)))}

      :else
      (or (some-> (find-skill-resource-by-uri resources uri)
                  (read-skill-entry-resource uri))
          (when-let [{:keys [name rel-path]} (parse-skill-sibling-uri uri)]
            (some-> (find-skill-by-name resources name)
                    (read-skill-sibling-resource uri rel-path)))))))

(defn folder-name?
  "Whether `s` names a folder directly under another one: what an extension's or a
   skill's name must be for its skills to be installed in the skills home."
  [s]
  (and (string? s)
       (not (string/blank? s))
       (not (contains? #{"." ".."} s))
       (= s (path/basename s))
       (not (string/includes? s "\\"))))

(def ^:private catalog-pointer
  "Tools are listed by `tools/list`. Skills are listed by `resources/list` and `skill://index.json` (read with `resources/read`).")

(defn- skills-home-text
  "What an agent does with the skills of the extension `extension-name` in the skills
   home."
  [extension-name]
  (let [extension-dir (str "~/.config/vscode-mcp/skills/" extension-name)]
    (str "The skills this server publishes are installed in `" extension-dir "/`, one folder per skill, and put there again at every server start. "
         "If your harness doesn't give you this server's skills as skills, offer the user to link the ones that `resources/list` lists into the harness's skills folder, and link them when the user says yes. "
         "For Claude Code, that is `mkdir -p ~/.claude/skills && ln -s " extension-dir "/<skill> ~/.claude/skills/<skill>` for each skill's name. "
         "On Windows, make a junction instead, since `ln -s` in Git Bash makes a copy. "
         "This command is PowerShell, and Claude Code's shell on Windows is Git Bash, so run it through `powershell -Command '...'`, in single quotes so that `$HOME` is left to PowerShell: "
         "`New-Item -ItemType Directory -Force -Path $HOME\\.claude\\skills; New-Item -ItemType Junction -Path $HOME\\.claude\\skills\\<skill> -Target $HOME\\.config\\vscode-mcp\\skills\\" extension-name "\\<skill>`. "
         "Where such a link is already there, there's nothing to do: the skill behind it is kept current. "
         "Where an entry by the skill's name is there and isn't a link into `~/.config/vscode-mcp/skills/`, leave it, and tell the user it may be an older copy of the skill.")))

(def ^:private registry-pointer
  "The window registry, `~/.config/vscode-mcp/registry`, can list the editor windows that run this server; its `README.md` says how to attach to one, also without an MCP connection.")

(defn build-server-instructions
  "Returns initialize `instructions`: non-blank `:base-text`, a catalog pointer, where
   the skills are installed and what to do with them, when `:skills?` and the
   extension's name `:extension-name` is one the install installs skills under, and
   where the window registry is."
  [{:keys [base-text extension-name skills?]}]
  (string/join "\n\n" (remove string/blank? [base-text
                                             catalog-pointer
                                             (when (and skills?
                                                        (folder-name? extension-name))
                                               (skills-home-text extension-name))
                                             registry-pointer])))

(defn build-initialize-result
  "Generates an MCP `initialize` result map.
   Extracts `name` and `version` from the extension's package.json, which can be overridden via `opts`.
   `opts` may include:
   - `:name`: Override the server name.
   - `:version`: Override the server version.
   - `:base-text`: Prepend custom text to the generated server instructions."
  [manifest & [opts]]
  (let [server-name (or (:name opts) (:manifest/name manifest) "vscode-mcp-server")
        server-version (or (:version opts) (:manifest/version manifest) "0.0.0")
        instructions (build-server-instructions {:base-text (:base-text opts)
                                                 :extension-name (:manifest/name manifest)
                                                 :skills? (boolean (seq (:manifest/skills manifest)))})]
    {:protocolVersion "2024-11-05"
     :capabilities {:tools {}
                    :resources {}
                    :extensions {"io.modelcontextprotocol/skills" {}}}
     :instructions instructions
     :serverInfo {:name server-name
                  :version server-version}}))
