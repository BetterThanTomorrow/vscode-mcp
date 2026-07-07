(ns vscode-mcp.cursor
  (:require
   ["vscode" :as vscode]
   [vscode-mcp.cursor-config :as config]
   [promesa.core :as p]))

(defn cursor-mcp-available? []
  (boolean
   (and (some? (.-cursor vscode))
        (some? (.-mcp (.-cursor vscode)))
        (fn? (.-registerServer (.-mcp (.-cursor vscode)))))))

(defn current-instance-slug
  [_]
  (config/instance-slug
   #:instance{:workspace-root-path (some-> ^js (first vscode/workspace.workspaceFolders)
                                           .-uri
                                           .-fsPath)}))

(def ^:private registered-names-key "vscode-mcp.cursor/registered-names")

(defn- read-registered-names [{:vscode/keys [^js extension-context]}]
  (when extension-context
    (js->clj (.get (.-workspaceState extension-context) registered-names-key))))

(defn- write-registered-names!+ [{:vscode/keys [^js extension-context]} names]
  (if extension-context
    (-> (.update (.-workspaceState extension-context)
                 registered-names-key
                 (clj->js names))
        (p/then (fn [_] {:ok true}))
        (p/catch (fn [err] {:ok false :error err})))
    (p/resolved {:ok false :reason :missing-extension-context})))

(defn unregister-by-name!+
  [server-name]
  (if (cursor-mcp-available?)
    (-> (.unregisterServer (.-mcp (.-cursor vscode)) server-name)
        (p/then (fn [_] {:ok true :name server-name}))
        (p/catch (fn [err] {:ok false :name server-name :error err})))
    (p/resolved {:ok false :reason :cursor-api-unavailable})))

(defn cleanup-tracked-registrations!+
  "Unregisters every tracked name except `keep-name`."
  [{:vscode/keys [^js extension-context] :as options} keep-name]
  (let [tracked (set (read-registered-names options))
        stale (remove #{keep-name} tracked)]
    (p/let [_ (p/all (map unregister-by-name!+ stale))]
      (write-registered-names!+ options (if keep-name [keep-name] [])))))

(defn- port-file-ready?+ [^js port-file-uri]
  (if port-file-uri
    (-> (vscode/workspace.fs.stat port-file-uri)
        (p/then (fn [_] true))
        (p/catch (fn [_] false)))
    (p/resolved false)))

(defn- reload-mcp-client!+
  [{:vscode/keys [extension-context] :cursor/keys [server-name]}]
  (let [identifier (config/mcp-client-identifier {:vscode/extension-context extension-context
                                                    :cursor/server-name server-name})]
    (cond
      (not identifier)
      (p/resolved {:ok false :reason :missing-extension-context})

      :else
      (-> (vscode/commands.executeCommand
           "mcp.reloadClient"
           (clj->js {:identifier identifier}))
          (p/then (fn [result] {:ok true :identifier identifier :result result}))
          (p/catch (fn [err] {:ok false :identifier identifier :error err}))))))

(defn register-mcp-server!+
  "registerServer with port-file guard and stale-name sweep."
  [options]
  (p/let [{:keys [ok config reason]} (config/build-cursor-mcp-registration-config options)
          port-file-uri (:server/port-file-uri options)
          ready? (port-file-ready?+ port-file-uri)]
    (cond
      (not ok)
      (p/resolved {:ok false :reason reason})

      (not (cursor-mcp-available?))
      (p/resolved {:ok false :reason :cursor-api-unavailable})

      (not ready?)
      (p/resolved {:ok false :reason :port-file-not-ready})

      :else
      (p/let [register-result (-> (.registerServer (.-mcp (.-cursor vscode)) (clj->js config))
                                    (p/then (fn [_] {:ok true :config config}))
                                    (p/catch (fn [err] {:ok false :error err :config config})))]
        (if-not (:ok register-result)
          register-result
          (p/let [reload-result (reload-mcp-client!+ options)
                  _ (cleanup-tracked-registrations!+ options (:cursor/server-name options))]
            {:ok true :name (:cursor/server-name options) :reload reload-result}))))))
