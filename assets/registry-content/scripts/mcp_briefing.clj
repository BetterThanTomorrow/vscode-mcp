(ns mcp-briefing
  "Pure `--readme` / `--readme-tool` briefing helpers for `bb mcp`.")

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
