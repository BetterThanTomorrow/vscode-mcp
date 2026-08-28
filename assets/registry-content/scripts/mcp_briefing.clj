(ns mcp-briefing
  "Pure `--readme` / `--readme-tool` briefing helpers for `bb mcp`."
  (:require
   [clojure.string :as string]))

(def readme-requests
  [["initialize" {:clientInfo {:name "bb-mcp"}}]
   ["resources/list" {}]
   ["tools/list" {:includeUserDescription true}]])

(defn compose-readme-briefing
  "Builds the `--readme` result from initialize, resources/list, and tools/list."
  [init-result resources-result tools-result]
  {:serverInfo (:serverInfo init-result)
   :instructions (:instructions init-result)
   :description (:description init-result)
   :skills (mapv #(select-keys % [:name :uri :description])
                 (:resources resources-result))
   :tools (mapv #(select-keys % [:name :userDescription])
                (:tools tools-result))
   :next "Read relevant skills with `resources/read`. Inspect a tool with `bb mcp --readme-tool <name>`."})

(defn pick-readme-tool
  "Returns the `--readme-tool` result map, or nil when the name is missing."
  [tools-result tool-name]
  (when-let [tool (some #(when (= tool-name (:name %)) %) (:tools tools-result))]
    (-> tool
        (select-keys [:name :description :inputSchema])
        (assoc :next "Call it with `bb mcp tools/call --name <name>`."))))

(defn thread-readme-requests
  "Calls `request-method!` for each readme RPC. Accumulates `:mcp/readme-results`, or stops on `:mcp/error`."
  [ctx request-method!]
  (reduce (fn [acc [method params]]
            (if (:mcp/error acc)
              acc
              (let [next (request-method! acc method params)]
                (if (:mcp/error next)
                  next
                  (update next :mcp/readme-results (fnil conj []) (:mcp/result next))))))
          ctx
          readme-requests))

(defn run-readme!
  "Runs initialize, resources/list, and tools/list (with userDescription) and composes one briefing."
  [ctx request-method!]
  (let [done (thread-readme-requests ctx request-method!)]
    (if (:mcp/error done)
      done
      (let [[init resources tools] (:mcp/readme-results done)]
        (assoc ctx :mcp/result (compose-readme-briefing init resources tools))))))

(defn run-readme-tool!
  "Lists tools and returns one tool's name, description, and inputSchema."
  [ctx request-method! fail]
  (let [listed (request-method! ctx "tools/list" {})
        tool-name (get-in ctx [:mcp/opts :readme-tool])]
    (if (:mcp/error listed)
      listed
      (if-let [picked (pick-readme-tool (:mcp/result listed) tool-name)]
        (assoc ctx :mcp/result picked)
        (fail ctx "unknown-tool" (str "Unknown tool: " tool-name))))))

(defn- skill-lines
  [{:keys [name uri description]}]
  (cond-> [(str "  " name)]
    uri (conj (str "    " uri))
    description (conj (str "    " description))))

(defn- tool-lines
  [{:keys [name userDescription]}]
  (cond-> [(str "  " name)]
    userDescription (conj (str "    " userDescription))))

(defn format-readme-text
  "Renders a `--readme` briefing map as plain text."
  [{:keys [serverInfo description instructions skills tools next]}]
  (let [title (string/trim (str (:name serverInfo) " " (:version serverInfo)))]
    (->> (concat
          (when-not (string/blank? title) [title])
          (when (and description (not (string/blank? description))) ["" description])
          (when (and instructions (not (string/blank? instructions)))
            ["" "Instructions" "" instructions])
          ["" "Skills"]
          (mapcat skill-lines skills)
          ["" "Tools"]
          (mapcat tool-lines tools)
          (when next ["" next]))
         (remove nil?)
         (string/join "\n"))))

(defn maybe-plain-readme
  "When `:hreadme` is set and there is a result, adds `:mcp/plain-text`."
  [ctx]
  (if (and (get-in ctx [:mcp/opts :hreadme])
           (:mcp/result ctx)
           (not (:mcp/error ctx)))
    (assoc ctx :mcp/plain-text (format-readme-text (:mcp/result ctx)))
    ctx))

(defn plain-help-kind
  "Returns `:hhelp` or `:hreadme-tool` when that human-help flag is set."
  [opts]
  (cond
    (true? (:hhelp opts)) :hhelp
    (true? (:hreadme-tool opts)) :hreadme-tool
    :else nil))

(defn cli-help-text
  "Plain-text CLI usage. `opts-text` is babashka.cli format-opts output."
  [opts-text]
  (str opts-text
       "\n\nFirst command: --readme. Then --readme-tool. See bb-mcp.md."
       "\nPlain-text: --hhelp (usage). --hreadme is --readme as text. --hreadme-tool is --readme-tool help."))

(def readme-tool-help-text
  (str "--readme-tool <name> inspects one tool. Copy --server-name and --window-id from `bb list`.\n"
       "\n"
       "It calls tools/list (no extra arg) and prints that tool's name, description (modelDescription),\n"
       "inputSchema, and next. Unknown name is unknown-tool.\n"
       "\n"
       "Next: `bb mcp tools/call --name <name>`.\n"
       "Agents parse the JSON envelope. This flag prints this text instead."))

(defn plain-help-text
  "Returns the plain-text help for `kind`. `opts-text` is used by `:hhelp`."
  [kind opts-text]
  (case kind
    :hhelp (cli-help-text opts-text)
    :hreadme-tool readme-tool-help-text))
