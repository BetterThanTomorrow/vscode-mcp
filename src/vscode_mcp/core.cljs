(ns vscode-mcp.core
  "Lifecycle orchestration: `maybe-start!+` / `start!+` / `stop!+` take the
   current lifecycle state as an explicit argument and resolve to the next
   state. Consumers own storing that state.

   State/config helpers are re-exported from `vscode-mcp.lifecycle` so
   this namespace is the single require for consumers."
  (:require
   [promesa.core :as p]
   [vscode-mcp.cursor :as cursor]
   [vscode-mcp.lifecycle :as state]
   [vscode-mcp.manual-setup.dialog :as dialog]
   [vscode-mcp.policy :as policy]
   [vscode-mcp.server :as server]))

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

(defn- maybe-register!+
  [config state silent? started-server-info]
  (let [{:cursor/keys [server-name script-relative-path]
         :vscode/keys [extension-context]
         :mcp/keys [auto-register?]
         :server/keys [host]
         :lifecycle/keys [on-cursor-registered on-cursor-registration-failed]} config
        register-allowed? (policy/should-register-with-cursor?
                           {:mcp/auto-register? auto-register?
                            :mcp/cursor-available? (cursor-mode? config)
                            :mcp/port-file-present? (state/port-file-present? started-server-info)})
        state' (assoc state :lifecycle/server-info started-server-info)]
    (if (should-call-register-server? state' {:lifecycle/silent? silent?} register-allowed?)
      (-> (cursor/register-and-reload-mcp-client!+
           {:vscode/extension-context extension-context
            :cursor/server-name server-name
            :cursor/script-relative-path script-relative-path
            :server/port-file-uri (:server/port-file-uri started-server-info)
            :server/host host
            :lifecycle/silent? silent?})
          (p/then (fn [result]
                    (if (:ok result)
                      (do (notify! on-cursor-registered result)
                          (assoc state'
                                 :lifecycle/cursor-registered? true
                                 :lifecycle/cursor-register-called? true))
                      (do (notify! on-cursor-registration-failed result)
                          (assoc state' :lifecycle/cursor-register-called? true))))))
      (p/resolved state'))))

(defn- start-flow!+
  [config state silent?]
  (if (running? state)
    (p/resolved state)
    (let [{:vscode/keys [extension-context]
           :mcp/keys [on-request on-log on-error]
           :server/keys [host]
           :lifecycle/keys [port-file-uri+ request-port wrapper-path
                             on-running-changed on-starting-changed]} config
          strategy-opts {:lifecycle/cursor-mode? (cursor-mode? config)}
          port-file-uri (when port-file-uri+ (port-file-uri+ extension-context strategy-opts))
          request-port-value (when request-port (request-port extension-context strategy-opts))]
      (notify! on-starting-changed true)
      (-> (server/start-server!+ {:server/host host
                                  :server/request-port request-port-value
                                  :server/port-file-uri port-file-uri
                                  :mcp/on-request on-request
                                  :mcp/on-log on-log})
          (p/then (fn [started-server-info]
                    (notify! on-running-changed true started-server-info)
                    (maybe-register!+ config state silent? started-server-info)))
          (p/then (fn [state']
                    (notify! on-starting-changed false)
                    (when-not silent?
                      (dialog/show-manual-start-dialog!+
                       (wrapper-path extension-context (server-info state'))
                       (server-info state')
                       (select-keys config [:manual-setup/message-suffix])))
                    state'))
          (p/catch (fn [e]
                     (notify! on-starting-changed false)
                     (notify! on-error e)
                     state))))))

(defn maybe-start!+
  "Silent activation start: only starts when policy allows it (explicit
   auto-start, or Cursor auto-register with Cursor MCP API available).
   `silent?` controls whether the manual-start dialog is shown once the
   server starts."
  [config state silent?]
  (let [{:mcp/keys [auto-start? auto-register?]} config]
    (if (or (running? state)
            (policy/should-auto-start? {:mcp/auto-start? auto-start?
                                        :mcp/auto-register? auto-register?
                                        :mcp/cursor-available? (cursor/cursor-mcp-available?)}))
      (start-flow!+ config state silent?)
      (p/resolved state))))

(defn start!+
  "Manual (command-driven) start: starts unconditionally unless already
   running. `silent?` controls whether the manual-start dialog is shown."
  [config state silent?]
  (start-flow!+ config state silent?))

(defn stop!+
  "Stops the server, unregistering from Cursor first if registered.
   `silent?` false also shows the \"MCP server stopped\" message."
  [config state silent?]
  (if-not (running? state)
    (p/resolved state)
    (let [{:cursor/keys [server-name]
           :vscode/keys [extension-context]
           :mcp/keys [on-log]
           :lifecycle/keys [on-running-changed on-stopping-changed]} config
          info (server-info state)]
      (notify! on-stopping-changed true)
      (-> (if (:lifecycle/cursor-registered? state)
            (cursor/unregister-mcp-server!+ {:cursor/server-name server-name
                                             :vscode/extension-context extension-context})
            (p/resolved true))
          (p/then (fn [_] (server/stop-server!+ (assoc info :mcp/on-log on-log))))
          (p/then (fn [_]
                    (notify! on-running-changed false nil)
                    (notify! on-stopping-changed false)
                    (when-not silent?
                      (dialog/show-stopped-message!+ {:manual-setup/silent? false}))
                    (init-state)))))))
