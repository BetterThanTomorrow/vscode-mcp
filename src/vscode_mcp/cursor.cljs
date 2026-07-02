(ns vscode-mcp.cursor
  "Cursor MCP client registration.

   Consumer API: `cursor-mcp-available?`, for availability checks outside
   the lifecycle flow (e.g. status/when-context bookkeeping).

   `register-and-reload-mcp-client!+` is normally reached only through
   `vscode-mcp.core`'s start flow. Exception: dev-only hot-reload tooling
   that needs to re-assert registration for an already-running server
   (`start!+`/`maybe-start!+` no-op once running, so there's no other
   entry point for that).

   `unregister-mcp-server!+` is internal — used by `vscode-mcp.core`'s
   stop flow only."
  (:require
   ["vscode" :as vscode]
   [vscode-mcp.cursor-config :as config]
   [vscode-mcp.policy :as policy]
   [promesa.core :as p]))

(defn cursor-mcp-available? []
  (boolean
   (and (some? (.-cursor vscode))
        (some? (.-mcp (.-cursor vscode)))
        (fn? (.-registerServer (.-mcp (.-cursor vscode)))))))

(defn- last-registered-config-key [server-name]
  (str "vscode-mcp.cursor/last-registered-config:" server-name))

(defn read-last-registered-config
  [{:vscode/keys [^js extension-context] :cursor/keys [server-name]}]
  (when extension-context
    (.get (.-workspaceState extension-context)
          (last-registered-config-key server-name))))

(defn store-last-registered-config!+
  [{:vscode/keys [^js extension-context] :cursor/keys [server-name]} config]
  (if extension-context
    (-> (.update (.-workspaceState extension-context)
                (last-registered-config-key server-name)
                config)
        (p/then (fn [_] {:ok true}))
        (p/catch (fn [err] {:ok false :error err})))
    (p/resolved {:ok false :reason :missing-extension-context})))

(defn- pending-reload-after-unregister-key [server-name]
  (str "vscode-mcp.cursor/pending-reload-after-unregister:" server-name))

(defn read-pending-reload-after-unregister?
  [{:vscode/keys [^js extension-context] :cursor/keys [server-name]}]
  (boolean
   (when extension-context
     (.get (.-workspaceState extension-context)
           (pending-reload-after-unregister-key server-name)))))

(defn mark-pending-reload-after-unregister!+
  [{:vscode/keys [^js extension-context] :cursor/keys [server-name]}]
  (if extension-context
    (-> (.update (.-workspaceState extension-context)
                (pending-reload-after-unregister-key server-name)
                true)
        (p/then (fn [_] {:ok true}))
        (p/catch (fn [err] {:ok false :error err})))
    (p/resolved {:ok false :reason :missing-extension-context})))

(defn clear-pending-reload-after-unregister!+
  [{:vscode/keys [^js extension-context] :cursor/keys [server-name]}]
  (if extension-context
    (-> (.update (.-workspaceState extension-context)
                (pending-reload-after-unregister-key server-name)
                nil)
        (p/then (fn [_] {:ok true}))
        (p/catch (fn [err] {:ok false :error err})))
    (p/resolved {:ok false :reason :missing-extension-context})))

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

(defn- register-mcp-server!+ [options]
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
      (p/let [config (:config register-result)
              stored (read-last-registered-config options)
              config-changed? (config/registration-config-changed? stored config)
              pending-reload-after-unregister? (read-pending-reload-after-unregister? options)
              _ (store-last-registered-config!+ options config)
              reload-result (if (policy/should-reload-client?
                                 {:lifecycle/silent? (:lifecycle/silent? options)
                                  :cursor/config-changed? config-changed?
                                  :cursor/pending-reload-after-unregister? pending-reload-after-unregister?})
                              (reload-mcp-client!+ {:vscode/extension-context (:vscode/extension-context options)
                                                    :cursor/server-name (:cursor/server-name options)})
                              (p/resolved {:ok true :skipped :unchanged-config}))
              _ (clear-pending-reload-after-unregister!+ options)]
        (assoc register-result :reload reload-result)))))

(defn unregister-mcp-server!+ [options]
  (cond
    (not (cursor-mcp-available?))
    (p/resolved {:ok false :reason :cursor-api-unavailable})

    :else
    (-> (.unregisterServer (.-mcp (.-cursor vscode)) (:cursor/server-name options))
        (p/then (fn [_]
                  (p/let [_ (mark-pending-reload-after-unregister!+ options)]
                    {:ok true})))
        (p/catch (fn [err] {:ok false :error err})))))
