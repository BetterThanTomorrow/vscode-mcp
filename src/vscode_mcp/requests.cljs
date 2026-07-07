(ns vscode-mcp.requests
  "Paved-path MCP JSON-RPC dispatch over a Copilot manifest.

   Consumer API: `handle-manifest-request` — default `case` clause for
   initialize, tools/list, resources/list, resources/templates/list,
   resources/read (skills), and ping. Extensions implement `tools/call`
   themselves and may supply optional hooks for dynamic resources."
  (:require
   [promesa.core :as p]
   [vscode-mcp.manifest :as manifest]
   [vscode-mcp.responses :as responses]))

(defn- manifest-opts
  [{:keys [settings]}]
  {:settings (or settings {})})

(defn- public-resources
  [context opts]
  (->> (manifest/get-resources context (manifest-opts opts))
       (map #(dissoc % :skill-path))
       vec))

(defn- skill-read-result
  [context uri opts]
  (when-let [resource (manifest/read-resource context uri (manifest-opts opts))]
    {:contents [(dissoc resource :skill-path)]}))

(defn- success-for-read
  [id result-or-nil not-found-message]
  (if result-or-nil
    (responses/success-response id result-or-nil)
    (responses/error-response id -32602 not-found-message)))

(defn- resolve-read-result+
  [id context uri opts hook-result]
  (cond
    (p/promise? hook-result)
    (p/then hook-result
            (fn [result]
              (if result
                (responses/success-response id result)
                (success-for-read id (skill-read-result context uri opts) "Resource not found"))))

    hook-result
    (responses/success-response id hook-result)

    :else
    (success-for-read id (skill-read-result context uri opts) "Resource not found")))

(defn- handle-initialize
  [context id opts]
  (let [{:keys [initialize-opts initialize-merge settings]} opts
        init-opts (merge (or initialize-opts {}) {:settings settings})]
    (responses/success-response id
                                (merge (manifest/build-initialize-result context init-opts)
                                       initialize-merge))))

(defn- handle-tools-list
  [context id opts]
  (responses/success-response id
                              {:tools (manifest/get-tools context (manifest-opts opts))}))

(defn- handle-resources-list
  [context id opts]
  (responses/success-response id
                              {:resources (public-resources context opts)}))

(defn- handle-resource-templates-list
  [context id opts]
  (let [templates (if-let [f (:resource-templates+ opts)]
                    (f context opts)
                    [])]
    (if (p/promise? templates)
      (p/then templates #(responses/success-response id {:resourceTemplates %}))
      (responses/success-response id {:resourceTemplates templates}))))

(defn- handle-resources-read
  [context id params opts]
  (let [uri (:uri params)
        hook (:read-resource+ opts)]
    (if hook
      (resolve-read-result+ id context uri opts (hook context uri opts))
      (success-for-read id (skill-read-result context uri opts) "Resource not found"))))

(defn handle-manifest-request
  "Default MCP dispatch for manifest-backed methods. Always returns a
   JSON-RPC response or Promise for requests with `:id`; returns `nil`
   only for unhandled notifications (no `:id`)."
  [context {:keys [method params id]} opts]
  (case method
    "initialize" (handle-initialize context id opts)
    "tools/list" (handle-tools-list context id opts)
    "resources/list" (handle-resources-list context id opts)
    "resources/templates/list" (handle-resource-templates-list context id opts)
    "resources/read" (handle-resources-read context id params opts)
    "ping" (responses/success-response id {})
    (if id
      (responses/error-response id -32601 (str "Method not found: " method))
      nil)))
