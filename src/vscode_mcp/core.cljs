(ns vscode-mcp.core
  (:require
   [promesa.core :as p]
   [vscode-mcp.cursor :as cursor]
   [vscode-mcp.eca :as eca]
   [vscode-mcp.lifecycle :as state]
   [vscode-mcp.manual-setup.dialog :as dialog]
   [vscode-mcp.policy :as policy]
   [vscode-mcp.registry-writer :as registry-writer]
   [vscode-mcp.server :as server]
   [vscode-mcp.server-readiness :as server-readiness]
   [vscode-mcp.wrapper-install :as wrapper-install]))

(def init-state state/init-state)
(def running? state/running?)
(def server-info state/server-info)
(def cursor-registered? state/cursor-registered?)
(defn- resolve-wrapper-path
  [config]
  (wrapper-install/installed-path (:lifecycle/wrapper-install-dir config)
                                  (:cursor/script-relative-path config)))

(def create-config state/create-config)

(def update-registry!+ registry-writer/update-registry!+)

(defn- cursor-mode?
  [{:mcp/keys [auto-register?]}]
  (and (cursor/cursor-mcp-available?) (boolean auto-register?)))

(defn- notify!
  [f & args]
  (when f (apply f args)))

(defn- normalize-stop-options
  [stop-options]
  (if (map? stop-options)
    stop-options
    {:lifecycle/silent? (boolean stop-options)}))

(defn- do-register!+
  [config state started-server-info]
  (let [{:cursor/keys [server-name script-relative-path]
         :vscode/keys [extension-context]
         :server/keys [host]
         :lifecycle/keys [on-cursor-registered on-cursor-registration-failed]} config
        instance-slug (:server/instance-slug started-server-info)
        intent (state/registration-intent state server-name instance-slug)
        {:register/keys [unregister-name register-name generation]} intent
        register-opts {:cursor/server-name register-name
                       :vscode/extension-context extension-context
                       :cursor/script-relative-path script-relative-path
                       :server/port-file-uri (:server/port-file-uri started-server-info)
                       :server/host host}]
    (p/let [_ (when unregister-name (cursor/unregister-by-name!+ unregister-name))
            result (cursor/register-mcp-server!+ register-opts)]
      (if (:ok result)
        (do (notify! on-cursor-registered result)
            (assoc state
                   :lifecycle/server-info started-server-info
                   :lifecycle/registered-name register-name
                   :lifecycle/generation generation))
        (do (notify! on-cursor-registration-failed result)
            (assoc state :lifecycle/server-info started-server-info))))))

(defn- resolve-eca-port-file-uri
  [config started-server-info strategy-opts]
  (let [{:vscode/keys [extension-context]
         :lifecycle/keys [eca-port-file-uri+]} config
        primary-uri (:server/port-file-uri started-server-info)]
    (if eca-port-file-uri+
      (eca-port-file-uri+ extension-context strategy-opts)
      primary-uri)))

(defn- ensure-eca-port-file!+
  [config started-server-info strategy-opts]
  (let [primary-uri (:server/port-file-uri started-server-info)
        eca-uri (resolve-eca-port-file-uri config started-server-info strategy-opts)
        assigned-port (:server/assigned-port started-server-info)]
    (case (state/eca-port-mirror-action primary-uri eca-uri)
      :skip (p/resolved nil)
      :reuse (p/resolved eca-uri)
      :mirror (-> (server/write-port-file!+ config eca-uri assigned-port)
                  (p/then (constantly eca-uri))))))

(defn- maybe-register-eca!+
  [config started-server-info]
  (let [on-log (:mcp/on-log config)
        allowed? (policy/should-register-with-eca?
                  {:mcp/auto-register-eca? (:mcp/auto-register-eca? config)
                   :mcp/eca-available? (eca/eca-available?)
                   :mcp/port-file-present? (state/port-file-present? started-server-info)
                   :mcp/workspace-root-present? (eca/workspace-root-present?)})]
    (if-not allowed?
      (p/resolved nil)
      (p/let [eca-uri (or (:server/eca-port-file-uri started-server-info)
                          (:server/port-file-uri started-server-info))
              result (eca/register!+
                       {:cursor/server-name (:cursor/server-name config)
                        :cursor/script-relative-path (:cursor/script-relative-path config)
                        :cursor/wrapper-path (resolve-wrapper-path config)
                        :vscode/extension-context (:vscode/extension-context config)
                        :server/port-file-uri eca-uri
                        :server/host (:server/host config)})]
        (when (and (not (:ok result)) on-log)
          (on-log :warn "[MCP] ECA registration failed:" (pr-str result)))
        {:eca/result result}))))

(defn- wait-for-server-ready!+
  [started-server-info on-log]
  (let [host (:server/host started-server-info)
        port (:server/assigned-port started-server-info)]
    (if (and host port)
      (p/then (server-readiness/wait-for-socket-accepting!+ host port nil)
              (fn [ready?]
                (when (and (not ready?) on-log)
                  (on-log :warn "[MCP] Socket readiness probe timed out before Cursor registration; proceeding anyway"))
                started-server-info))
      (p/resolved started-server-info))))

(defn- register-after-start!+
  [config state {:keys [started-server-info strategy-opts register-allowed?]}]
  (let [info (assoc started-server-info
                    :server/instance-slug (:lifecycle/instance-slug strategy-opts)
                    :server/workspace-root (cursor/current-workspace-root))
        on-running-changed (:lifecycle/on-running-changed config)]
    (p/let [eca-uri (ensure-eca-port-file!+ config info strategy-opts)
            info' (cond-> info
                    eca-uri (assoc :server/eca-port-file-uri eca-uri))
            _ (notify! on-running-changed true info')
            state' (if register-allowed?
                     (do-register!+ config state info')
                     (assoc state :lifecycle/server-info info'))
            _ (maybe-register-eca!+ config info')]
      state')))

(defn- start-flow!+
  [config state silent? & [flow-opts]]
  (if (running? state)
    (p/resolved state)
    (do
      (wrapper-install/ensure-installed! config)
      (let [{:vscode/keys [extension-context]
             :mcp/keys [on-request on-log on-error]
             :server/keys [host]
             :lifecycle/keys [port-file-uri+ request-port
                              on-starting-changed]} config
            instance-slug (cursor/current-instance-slug extension-context)
            strategy-opts {:lifecycle/cursor-mode? (cursor-mode? config)
                           :lifecycle/instance-slug instance-slug}
            port-file-uri (when port-file-uri+ (port-file-uri+ extension-context strategy-opts))
            request-port-value (when request-port (request-port extension-context strategy-opts))
            register-allowed? (policy/should-register-on-start?
                               {:mcp/auto-register? (:mcp/auto-register? config)
                                :mcp/cursor-available? (cursor-mode? config)
                                :mcp/port-file-present? true
                                :lifecycle/skip-register? (:lifecycle/skip-register? flow-opts)})]
        (notify! on-starting-changed true)
        (-> (server/start-server!+ {:server/host host
                                    :server/request-port request-port-value
                                    :server/port-file-uri port-file-uri
                                    :mcp/on-request on-request
                                    :mcp/on-log on-log})
            (p/then (fn [started-server-info]
                      (wait-for-server-ready!+ started-server-info on-log)))
            (p/then (fn [started-server-info]
                      (register-after-start!+
                       config state
                       {:started-server-info started-server-info
                        :strategy-opts strategy-opts
                        :register-allowed? register-allowed?})))
            (p/then (fn [state']
                      (-> (registry-writer/on-started!+ config (server-info state'))
                          (p/catch (fn [err]
                                     (when on-log
                                       (on-log :warn "[MCP] registry start failed:" err))
                                     nil))
                          (p/then (fn [_] state')))))
            (p/then (fn [state']
                      (notify! on-starting-changed false)
                      (when-not silent?
                        (dialog/show-manual-start-dialog!+
                         (resolve-wrapper-path config)
                         (server-info state')
                         config))
                      state'))
            (p/catch (fn [e]
                       (notify! on-starting-changed false)
                       (notify! on-error e)
                       state)))))))

(defn- sweep-stale-registrations!+
  [{:cursor/keys [server-name] :vscode/keys [extension-context]}]
  (if (cursor/cursor-mcp-available?)
    (cursor/cleanup-tracked-registrations!+
     {:cursor/server-name server-name
      :vscode/extension-context extension-context}
     nil)
    (p/resolved nil)))

(defn maybe-start!+
  [config state silent?]
  (let [{:mcp/keys [auto-start? auto-register? auto-register-eca?]} config]
    (p/let [_ (when (and (cursor/cursor-mcp-available?) (not auto-register?))
                (sweep-stale-registrations!+ config))]
      (if (or (running? state)
              (policy/should-auto-start? {:mcp/auto-start? auto-start?
                                          :mcp/auto-register? auto-register?
                                          :mcp/cursor-available? (cursor/cursor-mcp-available?)
                                          :mcp/auto-register-eca? auto-register-eca?
                                          :mcp/eca-available? (eca/eca-available?)
                                          :mcp/workspace-root-present? (eca/workspace-root-present?)}))
        (start-flow!+ config state silent?)
        state))))

(defn start!+
  [config state silent?]
  (start-flow!+ config state silent?))

(defn register-with-cursor!+
  "Ensure the socket server is running, then register with Cursor using the
   uniform generation-suffixed name path."
  [config state]
  (if-not (cursor/cursor-mcp-available?)
    (p/resolved {:ok false :reason :cursor-api-unavailable :state state})
    (p/let [state' (if (running? state)
                       (p/resolved state)
                       (start-flow!+ config state true {:lifecycle/skip-register? true}))
            info (server-info state')]
      (if-not info
        (p/resolved {:ok false :reason :registration-failed :state state'})
        (p/let [state'' (do-register!+ config state' info)]
          {:ok (cursor-registered? state'')
           :state state''
           :reason (when-not (cursor-registered? state'') :registration-failed)})))))

(defn stop!+
  [config state stop-options]
  (let [{:lifecycle/keys [silent?]} (normalize-stop-options stop-options)]
    (if-not (running? state)
      (p/resolved state)
      (let [{:vscode/keys [extension-context]
             :mcp/keys [on-log]
             :lifecycle/keys [on-running-changed on-stopping-changed]} config
            info (server-info state)
            primary-uri (:server/port-file-uri info)
            eca-uri (:server/eca-port-file-uri info)
            registered (:lifecycle/registered-name state)
            generation (:lifecycle/generation state 0)]
        (registry-writer/on-stopping! config)
        (notify! on-stopping-changed true)
        (-> (server/stop-server!+ (assoc info :mcp/on-log on-log))
            (p/then (fn [_]
                      (when (= :mirror (state/eca-port-mirror-action primary-uri eca-uri))
                        (server/delete-port-file!+ {:mcp/on-log on-log} eca-uri))))
            (p/then (fn [_]
                      (when registered
                        (p/let [_ (cursor/unregister-by-name!+ registered)
                                _ (cursor/cleanup-tracked-registrations!+
                                   {:vscode/extension-context extension-context}
                                   nil)]
                          nil))))
            (p/then (fn [_]
                      (registry-writer/on-stopped! config info)
                      (notify! on-running-changed false nil)
                      (notify! on-stopping-changed false)
                      (when-not silent?
                        (dialog/show-stopped-message!+ (merge config
                                                              {:manual-setup/silent? false})))
                      (assoc (init-state)
                             :lifecycle/generation (if registered (inc generation) generation))))
            (p/catch (fn [_]
                       (registry-writer/on-stopped! config info)
                       (notify! on-running-changed false nil)
                       (notify! on-stopping-changed false)
                       (init-state))))))))
