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

(defn port-file-ready?+ [^js port-file-uri]
  (if port-file-uri
    (-> (vscode/workspace.fs.stat port-file-uri)
        (p/then (fn [_] true))
        (p/catch (fn [_] false)))
    (p/resolved false)))

(defn reload-mcp-client!+
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

(defn register-mcp-server!+ [options]
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
      (-> (.registerServer (.-mcp (.-cursor vscode)) (clj->js config))
          (p/then (fn [_] {:ok true :config config}))
          (p/catch (fn [err] {:ok false :error err :config config}))))))

(defn register-and-reload-mcp-client!+ [options]
  (p/let [register-result (register-mcp-server!+ options)]
    (if-not (:ok register-result)
      register-result
      (p/let [reload-result (reload-mcp-client!+ {:vscode/extension-context (:vscode/extension-context options)
                                                   :cursor/server-name (:cursor/server-name options)})]
        (assoc register-result :reload reload-result)))))

(defn unregister-mcp-server!+ [{:cursor/keys [server-name]}]
  (cond
    (not (cursor-mcp-available?))
    (p/resolved {:ok false :reason :cursor-api-unavailable})

    :else
    (-> (.unregisterServer (.-mcp (.-cursor vscode)) server-name)
        (p/then (fn [_] {:ok true}))
        (p/catch (fn [err] {:ok false :error err})))))
