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

(defn current-instance-slug
  "Computes the per-window instance slug from the current VS Code window.
   Pure of state — callers (normally `vscode-mcp.core`'s start flow) own
   keeping the result stable across a session via the lifecycle state."
  [^js extension-context]
  (config/instance-slug
   #:instance{:workspace-root-path (some-> ^js (first vscode/workspace.workspaceFolders)
                                           .-uri
                                           .-fsPath)
              :storage-uri-path (some-> extension-context
                                        .-storageUri
                                        .-fsPath)}))

(defn last-registered-config-key [server-name]
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

(defn- unregister-server-raw!+
  "unregisterServer without the pending-reload bookkeeping — used for
   sweeping stale names. Failures (e.g. name not registered) are returned,
   not thrown."
  [server-name]
  (if (cursor-mcp-available?)
    (-> (.unregisterServer (.-mcp (.-cursor vscode)) server-name)
        (p/then (fn [_] {:ok true :name server-name}))
        (p/catch (fn [err] {:ok false :name server-name :error err})))
    (p/resolved {:ok false :reason :cursor-api-unavailable})))

(defn cleanup-tracked-registrations!+
  "Sweeps stale Cursor registrations for this workspaces. Normal operation has 
   one current name; this mainly cleans up names left by slug-format changes 
   or folder-less windows. Every name except `keep-name` is unregistered and
   its stored config cleared. The tracked list becomes `[keep-name]`, or `[]`
   when nil. Pending-reload flags are left alone — the unregister flow depends
   on them surviving this sweep."
  [{:vscode/keys [^js extension-context] :as options} keep-name]
  (let [tracked (set (read-registered-names options))
        from-config-keys (when extension-context
                           (keep (fn [k]
                                   (let [prefix "vscode-mcp.cursor/last-registered-config:"]
                                     (when (.startsWith k prefix)
                                       (subs k (count prefix)))))
                                 (.keys (.-workspaceState extension-context))))
        stale (remove #{keep-name} (into tracked from-config-keys))]
    (p/let [_ (p/all (map unregister-server-raw!+ stale))
            _ (p/all (map (fn [stale-name]
                            (when extension-context
                              (.update (.-workspaceState extension-context)
                                       (last-registered-config-key stale-name)
                                       js/undefined)))
                          stale))]
      (write-registered-names!+ options (if keep-name [keep-name] [])))))

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

(defn- prepare-registration!+
  "Repairs Cursor's restored client state before registering this server.
   Window reload can leave a same-name restored client in an unspawnable
   'Client closed' state; removing it first gives registerServer a clean
   record to create. The stale-name sweep also prevents slug migrations from
   accumulating old registrations."
  [options]
  (p/let [_ (unregister-server-raw!+ (:cursor/server-name options))
          _ (cleanup-tracked-registrations!+ options nil)
          _ (mark-pending-reload-after-unregister!+ options)]
    {:ok true}))

(defn- register-mcp-server!+
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
      (p/let [_ (prepare-registration!+ options)]
        (-> (.registerServer (.-mcp (.-cursor vscode)) (clj->js config))
            (p/then (fn [_] {:ok true :config config}))
            (p/catch (fn [err] {:ok false :error err :config config})))))))

(defn register-and-reload-mcp-client!+ [options]
  (p/let [register-result (register-mcp-server!+ options)]
    (if-not (:ok register-result)
      register-result
      (p/let [config (:config register-result)
              stored (read-last-registered-config options)
              config-changed? (config/registration-config-changed? stored config)
              pending-reload-after-unregister? (read-pending-reload-after-unregister? options)
              _ (store-last-registered-config!+ options config)
              _ (cleanup-tracked-registrations!+ options (:cursor/server-name options))
              reload-result (if (policy/should-reload-client?
                                 {:lifecycle/silent? (:lifecycle/silent? options)
                                  :cursor/server-cold-started? (:cursor/server-cold-started? options)
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
    (-> (unregister-server-raw!+ (:cursor/server-name options))
        (p/then (fn [result]
                  (if (:ok result)
                    (p/let [_ (mark-pending-reload-after-unregister!+ options)
                            _ (cleanup-tracked-registrations!+ options nil)]
                      {:ok true})
                    result))))))
