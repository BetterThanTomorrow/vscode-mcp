(ns vscode-mcp.core
  (:require
   ["vscode" :as vscode]
   [promesa.core :as p]
   [vscode-mcp.cursor :as cursor]
   [vscode-mcp.eca :as eca]
   [vscode-mcp.lifecycle :as state]
   [vscode-mcp.manual-setup.dialog :as dialog]
   [vscode-mcp.policy :as policy]
   [vscode-mcp.port-file :as port-file]
   [vscode-mcp.registry :as registry]
   [vscode-mcp.registry-writer :as registry-writer]
   [vscode-mcp.server :as server]
   [vscode-mcp.server-readiness :as server-readiness]
   [vscode-mcp.wrapper-install :as wrapper-install]))

(def init-state state/init-state)
(def running? state/running?)
(def server-info state/server-info)
(def cursor-registered? state/cursor-registered?)

;; The server's state, which the library keeps for a consumer that hands the lifecycle
;; functions its config alone. The lifecycle state holds the server and its sockets,
;; so it is kept apart from the server's state, which is plain data.

(defonce ^:private !lifecycle-states (atom {}))

(defonce ^:private !server-states (atom {}))

(defn server-state
  "Returns the state of `config`'s server as plain data: whether it runs, starts or
   stops, where it listens, its port file, the error of a start that failed, its name
   in Cursor, and the error of a Cursor registration that failed."
  [config]
  (get @!server-states (:server/name config) state/stopped-server-state))

(defn- lifecycle-state
  [config]
  (get @!lifecycle-states (:server/name config) (init-state)))

(defn- change-server-state!
  "Puts `changes` in the state of `config`'s server, and reports the state through
   `:mcp/on-server-changed` when that changed it. Returns the state."
  [config changes]
  (let [before (server-state config)
        after (state/changed-server-state before changes)]
    (when (not= before after)
      (swap! !server-states assoc (:server/name config) after)
      (when-let [on-server-changed (:mcp/on-server-changed config)]
        (on-server-changed after)))
    after))

(defn- keep-lifecycle-state!
  "Keeps `lifecycle-state` for `config`'s server, and returns the server's state after it."
  [config lifecycle-state]
  (swap! !lifecycle-states assoc (:server/name config) lifecycle-state)
  (change-server-state! config (state/server-facts lifecycle-state)))

(defn- reporting
  "Returns `config` with the lifecycle callbacks that keep the server's state, in
   place of a consumer's own."
  [config]
  (assoc config
         :lifecycle/on-starting-changed
         (fn [starting?]
           (change-server-state! config {:server/starting? (boolean starting?)}))

         :lifecycle/on-stopping-changed
         (fn [stopping?]
           (change-server-state! config {:server/stopping? (boolean stopping?)}))

         :lifecycle/on-running-changed
         (fn [running? server-info]
           (change-server-state! config (dissoc (state/server-facts {:lifecycle/server-info (when running? server-info)})
                                                :lifecycle/registered-name)))

         :lifecycle/on-cursor-registered
         (fn [result]
           (change-server-state! config {:lifecycle/registered-name (:name result)
                                         :lifecycle/registration-error nil}))

         :lifecycle/on-cursor-registration-failed
         (fn [failure]
           (change-server-state! config {:lifecycle/registration-error (state/registration-error failure)}))

         :mcp/on-error
         (fn [error]
           (change-server-state! config {:server/error (or (ex-message error) (str error))}))))
(defn- resolve-wrapper-path
  [config]
  (wrapper-install/installed-path (:lifecycle/wrapper-install-dir config)
                                  (:cursor/script-relative-path config)))

(def create-config state/create-config)

(def primary-port-file-path port-file/primary-path)

(defn primary-port-file-uri
  "VS Code Uri for the port file."
  ([server-name window-id]
   (primary-port-file-uri nil server-name window-id))
  ([config server-name window-id]
   (vscode/Uri.file (port-file/primary-path config server-name window-id))))

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

(defn- maybe-register-eca!+
  "Registers with ECA using the library port file."
  [config started-server-info]
  (let [on-log (:mcp/on-log config)
        allowed? (policy/should-register-with-eca?
                  {:mcp/auto-register-eca? (:mcp/auto-register-eca? config)
                   :mcp/eca-available? (eca/eca-available?)
                   :mcp/port-file-present? (state/port-file-present? started-server-info)
                   :mcp/workspace-root-present? (eca/workspace-root-present?)})]
    (if-not allowed?
      (p/resolved nil)
      (p/let [result (eca/register!+
                      {:cursor/server-name (:cursor/server-name config)
                       :cursor/script-relative-path (:cursor/script-relative-path config)
                       :cursor/wrapper-path (resolve-wrapper-path config)
                       :vscode/extension-context (:vscode/extension-context config)
                       :registry/enabled? (:registry/enabled? config)
                       :server/port-file-uri (:server/port-file-uri started-server-info)
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
                    :server/workspace-root (cursor/current-workspace-location)
                    :server/workspace-folder (cursor/current-workspace-root)
                    :server/app-id (cursor/current-app-id))
        on-running-changed (:lifecycle/on-running-changed config)]
    (p/let [_ (notify! on-running-changed true info)
            state' (if register-allowed?
                     (do-register!+ config state info)
                     (assoc state :lifecycle/server-info info))
            _ (maybe-register-eca!+ config info)]
      state')))

(defn- start-flow!+
  [config state silent? & [flow-opts]]
  (if (running? state)
    (p/resolved state)
    (do
      (wrapper-install/ensure-installed! config)
      (let [{:vscode/keys [extension-context]
             :mcp/keys [on-request on-log on-error]
             :cursor/keys [server-name]
             :server/keys [host]
             :lifecycle/keys [request-port on-starting-changed]} config
            instance-slug (cursor/current-instance-slug extension-context)
            strategy-opts {:lifecycle/cursor-mode? (cursor-mode? config)
                           :lifecycle/instance-slug instance-slug}
            port-file-uri (primary-port-file-uri config server-name instance-slug)
            request-port-value (cond
                                 request-port (request-port extension-context strategy-opts)
                                 (:lifecycle/cursor-mode? strategy-opts) 0
                                 :else (:server/request-port config))
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

(defn- maybe-start-flow!+
  [config state silent?]
  (registry/set-extension-host-env!)
  (let [{:mcp/keys [auto-start? auto-register? auto-register-eca?]} config]
    (p/let [_ (port-file/sweep-stale!+ (port-file/port-dir config))
            _ (when (and (cursor/cursor-mcp-available?) (not auto-register?))
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

(defn- run-flow!+
  "Runs `flow`, a function of a config and a lifecycle state to a promise of the next
   lifecycle state, for `config`'s server with the state the library keeps. Returns a
   promise of the server's state."
  [config flow]
  (let [config (reporting config)]
    (p/then (flow config (lifecycle-state config))
            (fn [lifecycle-state]
              (keep-lifecycle-state! config lifecycle-state)))))

(defn maybe-start!+
  "Starts `config`'s server when its settings and what is installed call for it, without
   a message. Returns a promise of the server's state. With a lifecycle `state` and
   `silent?`, it returns a promise of the next lifecycle state, for a consumer that
   keeps that itself."
  ([config]
   (run-flow!+ config #(maybe-start-flow!+ %1 %2 true)))
  ([config state silent?]
   (maybe-start-flow!+ config state silent?)))

(defn start!+
  "Starts `config`'s server, and shows how to connect to it. Returns a promise of the
   server's state, with `:server/error` when the start failed. With a lifecycle `state`
   and `silent?`, it returns a promise of the next lifecycle state, for a consumer that
   keeps that itself."
  ([config]
   (run-flow!+ config (fn [config state]
                        (registry/set-extension-host-env!)
                        (start-flow!+ config state false))))
  ([config state silent?]
   (registry/set-extension-host-env!)
   (start-flow!+ config state silent?)))

(defn- register-flow!+
  "Ensure the socket server is running, then register with Cursor using the
   uniform generation-suffixed name path."
  [config state]
  (registry/set-extension-host-env!)
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

(defn- registration-failed!
  "Puts a failed Cursor registration in the state of `config`'s server, and returns the state."
  [config failure]
  (change-server-state! config {:lifecycle/registration-error (state/registration-error failure)}))

(defn- register-kept!+
  "Registers `config`'s server with Cursor, starting it when it doesn't run, with the
   state the library keeps. A server that this starts is kept also when the
   registration then fails. Returns a promise of the server's state."
  [config]
  (registry/set-extension-host-env!)
  (if-not (cursor/cursor-mcp-available?)
    (p/resolved (registration-failed! config {:reason :cursor-api-unavailable}))
    (-> (p/let [state (lifecycle-state config)
                started (if (running? state)
                          state
                          (start-flow!+ config state true {:lifecycle/skip-register? true}))
                _ (keep-lifecycle-state! config started)
                info (server-info started)]
          (if-not info
            (registration-failed! config {:reason :registration-failed})
            (p/let [registered (do-register!+ config started info)]
              (keep-lifecycle-state! config registered))))
        (p/catch (fn [error]
                   (registration-failed! config {:reason :registration-failed
                                                 :error error}))))))

(defn register-with-cursor!+
  "Registers `config`'s server with Cursor, starting it when it doesn't run. Returns a
   promise of the server's state, with `:lifecycle/registration-error` when it didn't
   register. With a lifecycle `state`, it returns a promise of `{:ok … :reason … :state …}`,
   for a consumer that keeps the lifecycle state itself."
  ([config]
   (register-kept!+ (reporting config)))
  ([config state]
   (register-flow!+ config state)))

(defn- stop-flow!+
  [config state stop-options]
  (let [{:lifecycle/keys [silent?]} (normalize-stop-options stop-options)]
    (if-not (running? state)
      (p/resolved state)
      (let [{:vscode/keys [extension-context]
             :mcp/keys [on-log]
             :lifecycle/keys [on-running-changed on-stopping-changed]} config
            info (server-info state)
            registered (:lifecycle/registered-name state)
            generation (:lifecycle/generation state 0)]
        (registry-writer/on-stopping! config)
        (notify! on-stopping-changed true)
        (-> (server/stop-server!+ (assoc info :mcp/on-log on-log))
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

(defn stop!+
  "Stops `config`'s server, with a message unless `stop-options` has `:lifecycle/silent?`.
   Returns a promise of the server's state. With a lifecycle `state`, it returns a
   promise of the next lifecycle state, for a consumer that keeps that itself."
  ([config]
   (stop!+ config nil))
  ([config stop-options]
   (run-flow!+ config #(stop-flow!+ %1 %2 stop-options)))
  ([config state stop-options]
   (stop-flow!+ config state stop-options)))
