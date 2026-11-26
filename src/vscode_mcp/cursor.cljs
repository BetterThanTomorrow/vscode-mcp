(ns vscode-mcp.cursor
  (:require
   ["fs" :as fs]
   ["path" :as path]
   ["vscode" :as vscode]
   [vscode-mcp.cursor-config :as config]))

(defn cursor-mcp-available? []
  (boolean
   (and (some? (.-cursor vscode))
        (some? (.-mcp (.-cursor vscode)))
        (fn? (.-registerServer (.-mcp (.-cursor vscode)))))))

(defn current-workspace-root
  []
  (some-> ^js (first vscode/workspace.workspaceFolders)
          .-uri
          .-fsPath))

(defn current-workspace-location
  "Path the instance slug hashes: workspace file, else first folder."
  []
  (or (some-> ^js vscode/workspace.workspaceFile .-fsPath)
      (current-workspace-root)))

(defn- product-application-name
  []
  (try
    (let [file (path/join (.-appRoot vscode/env) "product.json")]
      (when (fs/existsSync file)
        (not-empty
         (:applicationName
          (js->clj (js/JSON.parse (fs/readFileSync file "utf8"))
                   :keywordize-keys true)))))
    (catch :default _
      nil)))

(defn current-app-id
  []
  (config/app-id (product-application-name) (.-uriScheme vscode/env)))

(defn current-instance-slug
  [_]
  (config/instance-slug
   #:instance{:app-id (.-uriScheme vscode/env)
              :workspace-file-path (some-> ^js vscode/workspace.workspaceFile .-fsPath)
              :workspace-root-path (current-workspace-root)}))

(def ^:private registered-names-key "vscode-mcp.cursor/registered-names")

(defn- read-registered-names [{:vscode/keys [^js extension-context]}]
  (when extension-context
    (js->clj (.get (.-workspaceState extension-context) registered-names-key))))

(defn- ^:async write-registered-names!+ [{:vscode/keys [^js extension-context]} names]
  (if extension-context
    (try
      (await (.update (.-workspaceState extension-context)
                      registered-names-key
                      (clj->js names)))
      {:ok true}
      (catch :default err
        {:ok false :error err}))
    {:ok false :reason :missing-extension-context}))

(defn ^:async unregister-by-name!+
  [server-name]
  (if (cursor-mcp-available?)
    (try
      (await (.unregisterServer (.-mcp (.-cursor vscode)) server-name))
      {:ok true :name server-name}
      (catch :default err
        {:ok false :name server-name :error err}))
    {:ok false :reason :cursor-api-unavailable}))

(defn ^:async cleanup-tracked-registrations!+
  "Unregisters every tracked name except `keep-name`."
  [options keep-name]
  (let [tracked (set (read-registered-names options))
        stale (remove #{keep-name} tracked)]
    (await (js/Promise.all (into-array (map unregister-by-name!+ stale))))
    (await (write-registered-names!+ options (if keep-name [keep-name] [])))))

(defn- ^:async port-file-ready?+ [^js port-file-uri]
  (if port-file-uri
    (try
      (await (vscode/workspace.fs.stat port-file-uri))
      true
      (catch :default _
        false))
    false))

(defn- ^:async reload-mcp-client!+
  [{:vscode/keys [extension-context] server-name :server/name}]
  (let [identifier (config/mcp-client-identifier {:vscode/extension-context extension-context
                                                    :server/name server-name})]
    (cond
      (not identifier)
      {:ok false :reason :missing-extension-context}

      :else
      (try
        (let [result (await (vscode/commands.executeCommand
                             "mcp.reloadClient"
                             (clj->js {:identifier identifier})))]
          {:ok true :identifier identifier :result result})
        (catch :default err
          {:ok false :identifier identifier :error err})))))

(defn ^:async register-mcp-server!+
  "registerServer with port-file guard and stale-name sweep."
  [options]
  (let [{:keys [ok config reason]} (config/build-cursor-mcp-registration-config options)
        port-file-uri (:server/port-file-uri options)
        ready? (await (port-file-ready?+ port-file-uri))]
    (cond
      (not ok)
      {:ok false :reason reason}

      (not (cursor-mcp-available?))
      {:ok false :reason :cursor-api-unavailable}

      (not ready?)
      {:ok false :reason :port-file-not-ready}

      :else
      (let [register-result (try
                              (await (.registerServer (.-mcp (.-cursor vscode)) (clj->js config)))
                              {:ok true :config config}
                              (catch :default err
                                {:ok false :error err :config config}))]
        (if-not (:ok register-result)
          register-result
          (let [reload-result (await (reload-mcp-client!+ options))]
            (await (cleanup-tracked-registrations!+ options (:server/name options)))
            {:ok true :name (:server/name options) :reload reload-result}))))))
