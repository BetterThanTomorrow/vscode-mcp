(ns vscode-mcp.lifecycle
  "Stateless lifecycle orchestration: `maybe-start!+` / `start!+` / `stop!+`
   take the current lifecycle state as an explicit argument and resolve
   directly to the next state. Consumers own storing that state (BD
   `app-db`, Joyride `!app-db`) — this namespace holds nothing itself.

   Pure state/config helpers (`init-state`, `running?`, `server-info`,
   `create-config`, `should-call-register-server?`) live in
   `vscode-mcp.lifecycle.pure` and are re-exported here so this namespace
   remains the single require for consumers. `vscode-mcp.lifecycle.pure` is
   the namespace unit tests target, since — unlike this one — it never
   loads \"vscode\"."
  (:require
   [promesa.core :as p]
   [vscode-mcp.cursor :as cursor]
   [vscode-mcp.lifecycle.pure :as pure]
   [vscode-mcp.manual-setup.dialog :as dialog]
   [vscode-mcp.policy :as policy]
   [vscode-mcp.server :as server]))

(def init-state pure/init-state)
(def running? pure/running?)
(def server-info pure/server-info)
(def create-config pure/create-config)
(def should-call-register-server? pure/should-call-register-server?)

(defn cursor-mode?
  "Whether Cursor auto-registration is both enabled and possible right now."
  [{:mcp/keys [auto-register?]}]
  (and (cursor/cursor-mcp-available?) (boolean auto-register?)))

(defn- notify!
  [f & args]
  (when f (apply f args)))

(defn- maybe-register!+
  [config state opts started-server-info cursor-mode?]
  (let [{:cursor/keys [server-name script-relative-path]
         :vscode/keys [extension-context]
         :mcp/keys [auto-register?]
         :server/keys [host]
         :lifecycle/keys [on-cursor-registered on-cursor-registration-failed]} config
        register-allowed? (policy/should-register-with-cursor?
                            {:mcp/auto-register? auto-register?
                             :mcp/cursor-available? cursor-mode?
                             :mcp/port-file-present? (pure/port-file-present? started-server-info)})
        state' (assoc state :lifecycle/server-info started-server-info)]
    (if (should-call-register-server? state' opts register-allowed?)
      (-> (cursor/register-and-reload-mcp-client!+
           {:vscode/extension-context extension-context
            :cursor/server-name server-name
            :cursor/script-relative-path script-relative-path
            :server/port-file-uri (:server/port-file-uri started-server-info)
            :server/host host})
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
  [config state {:lifecycle/keys [silent?] :as opts}]
  (if (running? state)
    (p/resolved state)
    (let [{:vscode/keys [extension-context]
           :mcp/keys [on-request on-log on-error]
           :server/keys [host]
           :lifecycle/keys [port-file-uri+ request-port wrapper-path
                             on-running-changed on-starting-changed]} config
          cursor-mode? (cursor-mode? config)
          strategy-opts {:lifecycle/cursor-mode? cursor-mode?}
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
                    (maybe-register!+ config state opts started-server-info cursor-mode?)))
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
   auto-start, or Cursor auto-register with Cursor MCP API available)."
  ([config state] (maybe-start!+ config state {:lifecycle/silent? true}))
  ([config state opts]
   (let [{:mcp/keys [auto-start? auto-register?]} config]
     (if (or (running? state)
             (policy/should-auto-start? {:mcp/auto-start? auto-start?
                                         :mcp/auto-register? auto-register?
                                         :mcp/cursor-available? (cursor/cursor-mcp-available?)}))
       (start-flow!+ config state (merge {:lifecycle/silent? true} opts))
       (p/resolved state)))))

(defn start!+
  "Manual (command-driven) start: starts unconditionally unless already
   running, and shows the manual-setup dialog unless silent."
  ([config state] (start!+ config state {:lifecycle/silent? false}))
  ([config state opts]
   (start-flow!+ config state (merge {:lifecycle/silent? false} opts))))

(defn stop!+
  "Stops the server, unregistering from Cursor first if registered.
   Silent by default (deactivate); pass {:lifecycle/silent? false} for a
   manual stop that also shows the \"MCP server stopped\" message."
  ([config state] (stop!+ config state {:lifecycle/silent? true}))
  ([config state {:lifecycle/keys [silent?]}]
   (if-not (running? state)
     (p/resolved state)
     (let [{:cursor/keys [server-name]
            :mcp/keys [on-log]
            :lifecycle/keys [on-running-changed on-stopping-changed]} config
           info (server-info state)]
       (notify! on-stopping-changed true)
       (-> (if (:lifecycle/cursor-registered? state)
             (cursor/unregister-mcp-server!+ {:cursor/server-name server-name})
             (p/resolved true))
           (p/then (fn [_] (server/stop-server!+ (assoc info :mcp/on-log on-log))))
           (p/then (fn [_]
                     (notify! on-running-changed false nil)
                     (notify! on-stopping-changed false)
                     (when-not silent?
                       (dialog/show-stopped-message!+ {:manual-setup/silent? false}))
                     (init-state))))))))
