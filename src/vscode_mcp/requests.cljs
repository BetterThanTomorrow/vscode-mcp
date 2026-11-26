(ns vscode-mcp.requests
  "Paved-path MCP JSON-RPC dispatch over a Copilot manifest.

   Consumer API: `handle-manifest-request` — default `case` clause for
   initialize, tools/list, resources/list, resources/templates/list,
   resources/read (skills), and ping. Extensions implement `tools/call`
   themselves and may supply optional hooks for dynamic resources."
  (:require
   [vscode-mcp.manifest :as manifest]
   [vscode-mcp.responses :as responses]))

(defn- manifest-opts
  [{:keys [settings]}]
  {:settings (or settings {})})

(defn- public-resources
  [manifest-data opts]
  (->> (manifest/get-resources manifest-data (manifest-opts opts))
       (map #(dissoc % :skill-path))
       vec))

(defn- skill-read-result
  [manifest-data uri opts]
  (when-let [resource (manifest/read-resource manifest-data uri (manifest-opts opts))]
    {:contents [(dissoc resource :skill-path)]}))

(defn- thenable?
  "True for a promise of any kind: a native one, or promesa's."
  [x]
  (and (some? x)
       (fn? (unchecked-get x "then"))))

(defn- success-for-read
  [id result-or-nil not-found-message]
  (if result-or-nil
    (responses/success-response id result-or-nil)
    (responses/error-response id -32602 not-found-message)))

(defn- resolve-read-result+
  [id manifest-data uri opts hook-result]
  (cond
    (thenable? hook-result)
    (.then (js/Promise.resolve hook-result)
           (fn [result]
             (if result
               (responses/success-response id result)
               (success-for-read id (skill-read-result manifest-data uri opts) "Resource not found"))))

    hook-result
    (responses/success-response id hook-result)

    :else
    (success-for-read id (skill-read-result manifest-data uri opts) "Resource not found")))

(defn- deep-merge-capabilities
  [base overlay]
  (merge-with (fn [a b]
                (if (and (map? a) (map? b))
                  (merge a b)
                  b))
              (or base {})
              (or overlay {})))

(defn- handle-initialize
  [manifest-data id opts]
  (let [{:keys [initialize-opts initialize-merge settings]} opts
        init-opts (merge (or initialize-opts {}) {:settings settings})
        base (manifest/build-initialize-result manifest-data init-opts)
        overlay (or initialize-merge {})
        result (-> base
                   (merge (dissoc overlay :capabilities))
                   (assoc :capabilities
                          (deep-merge-capabilities (:capabilities base)
                                                   (:capabilities overlay))))]
    (responses/success-response id result)))

(defn- handle-tools-list
  [manifest-data id params opts]
  (responses/success-response id
                              {:tools (manifest/get-tools manifest-data
                                                          (assoc (manifest-opts opts)
                                                                 :includeUserDescription
                                                                 (true? (:includeUserDescription params))))}))

(defn- handle-resources-list
  [manifest-data id opts]
  (responses/success-response id
                              {:resources (public-resources manifest-data opts)}))

(defn- handle-resource-templates-list
  [manifest-data id opts]
  (let [templates (if-let [f (:resource-templates+ opts)]
                    (f manifest-data opts)
                    [])]
    (if (thenable? templates)
      (.then (js/Promise.resolve templates) #(responses/success-response id {:resourceTemplates %}))
      (responses/success-response id {:resourceTemplates templates}))))

(defn- handle-resources-read
  [manifest-data id params opts]
  (let [uri (:uri params)
        hook (:read-resource+ opts)]
    (if hook
      (resolve-read-result+ id manifest-data uri opts (hook manifest-data uri opts))
      (success-for-read id (skill-read-result manifest-data uri opts) "Resource not found"))))

(defn handle-manifest-request
  "Default MCP dispatch for manifest-backed methods. Always returns a
   JSON-RPC response or Promise for requests with `:id`; returns `nil`
   only for unhandled notifications (no `:id`)."
  [manifest-data {:keys [method params id]} opts]
  (case method
    "initialize" (handle-initialize manifest-data id opts)
    "tools/list" (handle-tools-list manifest-data id params opts)
    "resources/list" (handle-resources-list manifest-data id opts)
    "resources/templates/list" (handle-resource-templates-list manifest-data id opts)
    "resources/read" (handle-resources-read manifest-data id params opts)
    "ping" (responses/success-response id {})
    (if id
      (responses/error-response id -32601 (str "Method not found: " method))
      nil)))
