(ns vscode-mcp.core
  "The library's entry points. Each waits for the lifecycle call before it for the same
   server, keeps the config for the effects, gathers what the decisions need from the
   editor, and dispatches one action to the loop. The actions
   are in `vscode-mcp.lifecycle-axs`, and the effects in `vscode-mcp.lifecycle-fxs`.

   The state is the servers' states by server name, as plain data."
  (:require
   ["vscode" :as vscode]
   [vscode-mcp.cursor :as cursor]
   [vscode-mcp.eca :as eca]
   [vscode-mcp.event-handler :as event-handler]
   [vscode-mcp.lifecycle :as lifecycle]
   [vscode-mcp.lifecycle-axs :as lifecycle-axs]
   [vscode-mcp.lifecycle-fxs :as lifecycle-fxs]
   [vscode-mcp.lifecycle-turns :as lifecycle-turns]
   [vscode-mcp.port-file :as port-file]
   [vscode-mcp.registry :as registry]
   [vscode-mcp.registry-writer :as registry-writer]))

(defonce ^:private !state (atom {}))

(defn- dispatch!
  "Dispatches `actions` to the library's loop. Returns a promise of the chain's last
   awaited result."
  [actions]
  (event-handler/dispatch!+ {:uf/!state !state
                             :uf/action-table lifecycle-axs/actions
                             :uf/effect-table lifecycle-fxs/effects
                             :uf/dispatch! dispatch!
                             :uf/state-effects lifecycle-axs/state-effects}
                            actions))

(defn- facts
  "Returns what the actions decide from, for `config`: the config's data that they read,
   and what the editor says now."
  [{:vscode/keys [extension-context] :as config}]
  (assoc (select-keys config [:server/name :server/host :server/request-port
                              :mcp/auto-start? :mcp/auto-register? :mcp/auto-register-eca?])
         :mcp/cursor-available? (cursor/cursor-mcp-available?)
         :mcp/eca-available? (eca/eca-available?)
         :mcp/workspace-root-present? (eca/workspace-root-present?)
         :server/instance-slug (cursor/current-instance-slug extension-context)
         :server/workspace-root (cursor/current-workspace-location)
         :server/workspace-folder (cursor/current-workspace-root)
         :server/app-id (cursor/current-app-id)))

(def ^:private silent-stop-wait-ms
  "How long the silent stop at deactivation waits for a call before it that doesn't
   settle. VS Code gives deactivation a few seconds before it ends the Extension Host,
   so the stop waits less than that, and still has time to run."
  3000)

(defn- turn-options
  "How a call for `config`'s server waits for the one before it: `wait-ms` at most, and
   then it logs that it runs anyway."
  ([config]
   (turn-options config lifecycle-turns/default-wait-ms))
  ([{server-name :server/name :mcp/keys [on-log]} wait-ms]
   {:turns/wait-ms wait-ms
    :turns/on-waited-out (fn []
                           (when on-log
                             (on-log :warn "[MCP] A lifecycle call for" server-name
                                     "hasn't settled after" wait-ms "ms; the next one runs anyway")))}))

(defn- dispatch-for!+
  "When the lifecycle call before it for `config`'s server has settled, or has been
   waited for as long as `waiting` says, keeps `config` for the effects, and
   dispatches the action `action-name` with the facts for `config` and `more-facts`.
   Returns a promise of the server's state."
  [config waiting action-name more-facts]
  (lifecycle-turns/in-turn+ (:server/name config)
                            waiting
                            (fn []
                              (lifecycle-fxs/keep-config! config)
                              (dispatch! [[action-name (merge (facts config) more-facts)]]))))

(def create-config lifecycle/create-config)

(def primary-port-file-path port-file/primary-path)

(defn primary-port-file-uri
  "VS Code Uri for the port file."
  ([server-name window-id]
   (primary-port-file-uri nil server-name window-id))
  ([config server-name window-id]
   (vscode/Uri.file (port-file/primary-path config server-name window-id))))

(defn update-registry!+
  "When the lifecycle call before it for `config`'s server has settled, takes the
   config's `:registry/custom-data` for the running server's entry, written after the
   debounce time. So the data given during a start is in the entry after it."
  [config]
  (lifecycle-turns/in-turn+ (:server/name config)
                            (turn-options config)
                            #(registry-writer/update-registry!+ config)))

(defn server-state
  "Returns the state of `config`'s server as plain data: whether it runs, starts or
   stops, where it listens, its port file, the error of a start that failed, its name
   in Cursor, and the error of a Cursor registration that failed."
  [config]
  (lifecycle-axs/server-state @!state (:server/name config)))

(defn maybe-start!+
  "Starts `config`'s server when its settings and what is installed call for it, without
   a message. Returns a promise of the server's state."
  [config]
  (registry/set-extension-host-env!)
  (dispatch-for!+ config (turn-options config) :lifecycle/ax.maybe-start nil))

(defn start!+
  "Starts `config`'s server, and shows how to connect to it. Returns a promise of the
   server's state, with `:server/error` when the start failed."
  [config]
  (registry/set-extension-host-env!)
  (dispatch-for!+ config (turn-options config) :lifecycle/ax.start {:lifecycle/silent? false}))

(defn register-with-cursor!+
  "Registers `config`'s server with Cursor, starting it when it doesn't run. Returns a
   promise of the server's state, with `:lifecycle/registration-error` when it didn't
   register."
  [config]
  (registry/set-extension-host-env!)
  (dispatch-for!+ config (turn-options config) :lifecycle/ax.register-with-cursor nil))

(defn stop!+
  "Stops `config`'s server, with a message unless `stop-options` has `:lifecycle/silent?`.
   A silent stop, the one at deactivation, waits for a call before it that doesn't
   settle for `silent-stop-wait-ms` only. Returns a promise of the server's state."
  ([config]
   (stop!+ config nil))
  ([config stop-options]
   (let [silent? (boolean (if (map? stop-options)
                            (:lifecycle/silent? stop-options)
                            stop-options))]
     (dispatch-for!+ config
                     (turn-options config (if silent?
                                            silent-stop-wait-ms
                                            lifecycle-turns/default-wait-ms))
                     :lifecycle/ax.stop
                     {:lifecycle/silent? silent?}))))
