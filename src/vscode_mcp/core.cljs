(ns vscode-mcp.core
  "Lifecycle orchestration: `maybe-start!+` / `start!+` / `stop!+` take the
   current lifecycle state as an explicit argument and resolve to the next
   state. Consumers own storing that state.

   `:cursor/server-name` in the config is a base name (e.g. \"joyride\");
   the start flow suffixes it with a per-window instance slug so windows
   don't overwrite each other's Cursor registrations. The slug is carried
   in server-info as `:server/instance-slug` (so stop/unregister agree with
   registration) and passed to the `:lifecycle/port-file-uri+` callback as
   `:lifecycle/instance-slug` for slug-scoped port-file paths.

   State/config helpers are re-exported from `vscode-mcp.lifecycle` so
   this namespace is the single require for consumers."
  (:require
   [promesa.core :as p]
   [vscode-mcp.cursor :as cursor]
   [vscode-mcp.cursor-config :as cursor-config]
   [vscode-mcp.lifecycle :as state]
   [vscode-mcp.manual-setup.dialog :as dialog]
   [vscode-mcp.policy :as policy]
   [vscode-mcp.server :as server]
   [vscode-mcp.server-readiness :as server-readiness]))

(def init-state state/init-state)
(def running? state/running?)
(def server-info state/server-info)
(def create-config state/create-config)
(def should-call-register-server? state/should-call-register-server?)

(defn- cursor-mode?
  "Whether Cursor auto-registration is both enabled and possible right now."
  [{:mcp/keys [auto-register?]}]
  (and (cursor/cursor-mcp-available?) (boolean auto-register?)))

(defn- notify!
  [f & args]
  (when f (apply f args)))

(defn- normalize-stop-options
  "Accepts a stop-options map or a legacy boolean `silent?` for backward
   compatibility with consumers not yet on the map API."
  [stop-options]
  (cond
    (map? stop-options) stop-options
    (boolean? stop-options) {:lifecycle/silent? stop-options}
    :else {:lifecycle/silent? true}))

(defn- register-cursor-options
  [state register-opts]
  (let [{:lifecycle/keys [force-register?]
         :cursor/keys [server-cold-started? force-reload?]} register-opts
        needs-reregister? (:lifecycle/needs-cursor-reregister? state)]
    {:cursor/server-cold-started? (if (contains? register-opts :cursor/server-cold-started?)
                                    server-cold-started?
                                    true)
     :cursor/needs-cursor-reregister? needs-reregister?
     :cursor/force-reload? (or force-reload? needs-reregister?)}))

(defn- maybe-register!+
  "Registers with Cursor right after a server start. `:cursor/server-cold-started?`
   defaults true for the start flow; warm repair passes false via register-opts."
  ([config state silent? started-server-info]
   (maybe-register!+ config state silent? started-server-info {}))
  ([config state silent? started-server-info register-opts]
   (let [{:lifecycle/keys [force-register?]} register-opts
         {:cursor/keys [server-name script-relative-path]
          :vscode/keys [extension-context]
          :mcp/keys [auto-register?]
          :server/keys [host]
          :lifecycle/keys [on-cursor-registered on-cursor-registration-failed]} config
         register-allowed? (or force-register?
                               (policy/should-register-with-cursor?
                                 {:mcp/auto-register? auto-register?
                                  :mcp/cursor-available? (cursor-mode? config)
                                  :mcp/port-file-present? (state/port-file-present? started-server-info)}))
         state' (assoc state :lifecycle/server-info started-server-info)
         cursor-opts (register-cursor-options state' register-opts)
         needs-reregister? (:lifecycle/needs-cursor-reregister? state')
         registration-silent? (if needs-reregister? false silent?)]
     (if (should-call-register-server? state' (merge {:lifecycle/silent? silent?} register-opts) register-allowed?)
       (-> (cursor/register-and-reload-mcp-client!+
            (merge {:vscode/extension-context extension-context
                    :cursor/server-name (cursor-config/slugged-server-name
                                          server-name
                                          (:server/instance-slug started-server-info))
                    :cursor/script-relative-path script-relative-path
                    :server/port-file-uri (:server/port-file-uri started-server-info)
                    :server/host host
                    :lifecycle/silent? registration-silent?}
                   cursor-opts
                   (when needs-reregister?
                     {:cursor/skip-prepare-registration? true})))
           (p/then (fn [result]
                     (if (:ok result)
                       (do (notify! on-cursor-registered result)
                           (assoc state'
                                  :lifecycle/cursor-registered? true
                                  :lifecycle/cursor-register-called? true
                                  :lifecycle/needs-cursor-reregister? false))
                       (do (notify! on-cursor-registration-failed result)
                           (assoc state' :lifecycle/cursor-register-called? true))))))
       (p/resolved state')))))

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

(defn- start-flow!+
  [config state silent?]
  (if (running? state)
    (p/resolved state)
    (let [{:vscode/keys [extension-context]
           :mcp/keys [on-request on-log on-error]
           :server/keys [host]
           :lifecycle/keys [port-file-uri+ request-port wrapper-path
                             on-running-changed on-starting-changed]} config
          instance-slug (cursor/current-instance-slug extension-context)
          strategy-opts {:lifecycle/cursor-mode? (cursor-mode? config)
                         :lifecycle/instance-slug instance-slug}
          port-file-uri (when port-file-uri+ (port-file-uri+ extension-context strategy-opts))
          request-port-value (when request-port (request-port extension-context strategy-opts))
          register-opts {:cursor/server-cold-started? true
                         :cursor/force-reload? (:lifecycle/needs-cursor-reregister? state)}]
      (notify! on-starting-changed true)
      (-> (server/start-server!+ {:server/host host
                                  :server/request-port request-port-value
                                  :server/port-file-uri port-file-uri
                                  :mcp/on-request on-request
                                  :mcp/on-log on-log})
          (p/then (fn [started-server-info]
                    (wait-for-server-ready!+ started-server-info on-log)))
          (p/then (fn [started-server-info]
                    (let [info (assoc started-server-info :server/instance-slug instance-slug)]
                      (notify! on-running-changed true info)
                      (maybe-register!+ config state silent? info register-opts))))
          (p/then (fn [state']
                    (notify! on-starting-changed false)
                    (when-not silent?
                      (dialog/show-manual-start-dialog!+
                       (wrapper-path extension-context (server-info state'))
                       (server-info state')
                       config))
                    state'))
          (p/catch (fn [e]
                     (notify! on-starting-changed false)
                     (notify! on-error e)
                     state))))))

(defn- maybe-unregister-stale!+
  "When Cursor is available but auto-register is disabled, removes any
   leftover registration for this window from a previous session. Failures
   (e.g. nothing registered) are ignored."
  [{:cursor/keys [server-name] :vscode/keys [extension-context] :mcp/keys [auto-register?]}]
  (if (and (cursor/cursor-mcp-available?) (not auto-register?))
    (cursor/unregister-mcp-server!+
     {:cursor/server-name (cursor-config/slugged-server-name
                           server-name
                           (cursor/current-instance-slug extension-context))
      :vscode/extension-context extension-context})
    (p/resolved nil)))

(defn maybe-start!+
  "Silent activation start: only starts when policy allows it (explicit
   auto-start, or Cursor auto-register with Cursor MCP API available).
   When auto-register is disabled, unregisters any leftover Cursor
   registration for this window instead.
   `silent?` controls whether the manual-start dialog is shown once the
   server starts."
  [config state silent?]
  (let [{:mcp/keys [auto-start? auto-register?]} config]
    (p/let [_ (maybe-unregister-stale!+ config)]
      (if (or (running? state)
              (policy/should-auto-start? {:mcp/auto-start? auto-start?
                                          :mcp/auto-register? auto-register?
                                          :mcp/cursor-available? (cursor/cursor-mcp-available?)}))
        (start-flow!+ config state silent?)
        state))))

(defn start!+
  "Manual (command-driven) start: starts unconditionally unless already
   running. `silent?` controls whether the manual-start dialog is shown."
  [config state silent?]
  (start-flow!+ config state silent?))

(defn register-with-cursor!+
  "Register the currently running MCP server with Cursor (warm repair path).
   Server must already be running. Always non-silent so mcp.reloadClient runs.
   Resolves to `{:ok :state :reason :register-result}`."
  [config state]
  (if-not (running? state)
    (p/resolved {:ok false :reason :server-not-running :state state})
    (p/let [info (server-info state)
            state' (maybe-register!+
                     config
                     (dissoc state :lifecycle/cursor-registered? :lifecycle/cursor-register-called?)
                     false
                     info
                     {:lifecycle/force-register? true
                      :cursor/server-cold-started? false})]
      {:ok (boolean (:lifecycle/cursor-registered? state'))
       :state state'
       :reason (when-not (:lifecycle/cursor-registered? state') :registration-failed-or-skipped)})))

(defn register-or-start-with-cursor!+
  "Option C semantics: when `:mcp/auto-register?` is false, starts the server
   if needed then registers; when auto-register is on, repair-only (server must
   already be running)."
  [config state]
  (cond
    (not (cursor/cursor-mcp-available?))
    (p/resolved {:ok false :reason :cursor-api-unavailable :state state})

    (and (:lifecycle/cursor-registered? state) (running? state))
    (p/resolved {:ok true :state state :reason :already-registered})

    (running? state)
    (register-with-cursor!+ config state)

    (:mcp/auto-register? config)
    (p/resolved {:ok false :reason :server-not-running :state state})

    :else
    (p/let [state' (start-flow!+ config state true)]
      (register-with-cursor!+ config state'))))

(defn stop!+
  "Stops the server. `stop-options` map:
   `:lifecycle/silent?` — false also shows the \"MCP server stopped\" message."
  [config state stop-options]
  (let [{:lifecycle/keys [silent?]} (normalize-stop-options stop-options)]
    (if-not (running? state)
      (p/resolved state)
      (let [{:cursor/keys [server-name]
             :vscode/keys [extension-context]
             :mcp/keys [on-log]
             :lifecycle/keys [on-running-changed on-stopping-changed]} config
            info (server-info state)]
        (notify! on-stopping-changed true)
        (-> (cursor/unregister-mcp-server!+ {:cursor/server-name (cursor-config/slugged-server-name
                                                                  server-name
                                                                  (:server/instance-slug info))
                                             :vscode/extension-context extension-context})
            (p/then (fn [_] (server/stop-server!+ (assoc info :mcp/on-log on-log))))
            (p/then (fn [_]
                      (notify! on-running-changed false nil)
                      (notify! on-stopping-changed false)
                      (when-not silent?
                        (dialog/show-stopped-message!+ (merge config
                                                              {:manual-setup/silent? false})))
                      (assoc (init-state) :lifecycle/needs-cursor-reregister? true))))))))
