(ns vscode-mcp.lifecycle-fxs
  "What the library does to a server and to what is around it: the effects for the loop
   in `vscode-mcp.event-handler`, which the actions in `vscode-mcp.lifecycle-axs` name.

   An effect that the loop awaits resolves to data, also when what it did failed, so the
   action that takes its result decides what the failure means.

   The live objects are kept here, by server name: the config as the consumer handed it
   over, with its functions and the extension context, and the server that listens."
  (:require
   ["vscode" :as vscode]
   [vscode-mcp.cursor :as cursor]
   [vscode-mcp.eca :as eca]
   [vscode-mcp.manual-setup.dialog :as dialog]
   [vscode-mcp.port-file :as port-file]
   [vscode-mcp.registry-writer :as registry-writer]
   [vscode-mcp.server :as server]
   [vscode-mcp.server-readiness :as server-readiness]
   [vscode-mcp.wrapper-install :as wrapper-install]))

(defonce ^:private !live-objects (atom {}))

(defn keep-config!
  "Keeps `config` for its server, for the effects to read."
  [config]
  (swap! !live-objects assoc-in [(:server/name config) :live/config] config))

(defn- config-of [server-name]
  (get-in @!live-objects [server-name :live/config]))

(defn- server-info-of
  "Returns what `server/start-server!+` resolved to for the server that listens: the
   server, its sockets and its port file's `Uri`, among what it listens on."
  [server-name]
  (get-in @!live-objects [server-name :live/server-info]))

(defn- error-message [e]
  (or (ex-message e) (str e)))

(defn- wrapper-path [config]
  (wrapper-install/installed-path (:lifecycle/wrapper-install-dir config)
                                  (:wrapper/script-relative-path config)))

(defn- return
  "Resolves the dispatch to `v`."
  [_dispatch! v]
  v)

(defn- report-server-state
  [_dispatch! server-name server-state]
  (when-let [on-server-changed (:mcp/on-server-changed (config-of server-name))]
    (on-server-changed server-state)))

(defn- ^:async start-server
  "Installs the wrapper and starts the server. Resolves to what the server listens on,
   or to `{:error/message …}` for a start that failed, which leaves no server."
  [_dispatch! {server-name :server/name :server/keys [host request-port instance-slug]}]
  (let [{:mcp/keys [on-request on-log] :as config} (config-of server-name)
        port-file-uri (vscode/Uri.file (port-file/primary-path config server-name instance-slug))]
    (try
      (wrapper-install/ensure-installed! config)
      (let [started (await (server/start-server!+ {:server/host host
                                                   :server/request-port request-port
                                                   :server/port-file-uri port-file-uri
                                                   :mcp/on-request on-request
                                                   :mcp/on-log on-log}))]
        (swap! !live-objects assoc-in [server-name :live/server-info] started)
        {:server/host (:server/host started)
         :server/assigned-port (:server/assigned-port started)
         :server/port-file-path (.-fsPath port-file-uri)
         :server/port-note (:server/port-note started)})
      (catch :default e
        {:error/message (error-message e)}))))

(defn- ^:async wait-until-ready
  "Waits until the server takes a connection. Resolves to whether it did."
  [_dispatch! server-name]
  (let [{:server/keys [host assigned-port]} (server-info-of server-name)
        on-log (:mcp/on-log (config-of server-name))
        ready? (boolean (when (and host assigned-port)
                          (try
                            (await (server-readiness/wait-for-socket-accepting!+ host assigned-port nil))
                            (catch :default _
                              false))))]
    (when (and host assigned-port (not ready?) on-log)
      (on-log :warn "[MCP] Socket readiness probe timed out before Cursor registration; proceeding anyway"))
    ready?))

(defn- ^:async register-with-cursor
  "Registers the server with Cursor as `intent` plans it. Resolves to `{:ok true}`, or
   to `{:ok false}` with the `:reason`, and the `:message` of what was thrown."
  [_dispatch! server-name {:register/keys [unregister-name register-name]}]
  (let [{:wrapper/keys [script-relative-path]
         :vscode/keys [extension-context]
         :server/keys [host]} (config-of server-name)]
    (try
      (when unregister-name
        (await (cursor/unregister-by-name!+ unregister-name)))
      (let [{:keys [ok reason error]} (await (cursor/register-mcp-server!+
                                              {:server/name register-name
                                               :vscode/extension-context extension-context
                                               :wrapper/script-relative-path script-relative-path
                                               :server/port-file-uri (:server/port-file-uri (server-info-of server-name))
                                               :server/host host}))]
        (if ok
          {:ok true}
          (cond-> {:ok false}
            reason (assoc :reason reason)
            error (assoc :message (error-message error)))))
      (catch :default e
        {:ok false
         :reason :registration-failed
         :message (error-message e)}))))

(defn- ^:async register-with-eca
  "Gives ECA its entry for the server. A registration that failed is logged."
  [_dispatch! server-name]
  (let [{:mcp/keys [on-log] :as config} (config-of server-name)
        result (try
                 (await (eca/register!+
                         {:server/name server-name
                          :wrapper/script-relative-path (:wrapper/script-relative-path config)
                          :cursor/wrapper-path (wrapper-path config)
                          :vscode/extension-context (:vscode/extension-context config)
                          :registry/enabled? (:registry/enabled? config)
                          :server/port-file-uri (:server/port-file-uri (server-info-of server-name))
                          :server/host (:server/host config)}))
                 (catch :default e
                   {:ok false :message (error-message e)}))]
    (when (and (not (:ok result)) on-log)
      (on-log :warn "[MCP] ECA registration failed:" (pr-str result)))
    nil))

(defn- ^:async registry-started
  "Writes the window's registry entry for the server, with `server` as its data."
  [_dispatch! server-name server]
  (let [{:mcp/keys [on-log] :as config} (config-of server-name)]
    (try
      (await (registry-writer/on-started!+ config (merge (server-info-of server-name) server)))
      (catch :default e
        (when on-log
          (on-log :warn "[MCP] registry start failed:" e))))
    nil))

(defn- show-start-message
  [_dispatch! server-name server]
  (let [config (config-of server-name)]
    (dialog/show-manual-start-dialog!+ (wrapper-path config)
                                       (merge (server-info-of server-name) server)
                                       config)))

(defn- sweep-port-files
  [_dispatch! server-name]
  (port-file/sweep-stale!+ (port-file/port-dir (config-of server-name))))

(defn- sweep-cursor-registrations
  [_dispatch! server-name]
  (cursor/cleanup-tracked-registrations!+
   {:server/name server-name
    :vscode/extension-context (:vscode/extension-context (config-of server-name))}
   nil))

(defn- registry-stopping
  [_dispatch! server-name]
  (registry-writer/on-stopping! (config-of server-name)))

(defn- ^:async stop-server
  "Stops the server and removes its port file. Resolves to whether it stopped."
  [_dispatch! server-name]
  (let [on-log (:mcp/on-log (config-of server-name))]
    (try
      (await (server/stop-server!+ (assoc (server-info-of server-name) :mcp/on-log on-log)))
      (catch :default _
        false))))

(defn- ^:async unregister-from-cursor
  "Takes `registered-name` from Cursor, and with it the names that earlier registrations
   left. Resolves to `{:ok true}`, or to `{:error/message …}`."
  [_dispatch! server-name registered-name]
  (try
    (await (cursor/unregister-by-name!+ registered-name))
    (await (cursor/cleanup-tracked-registrations!+
            {:vscode/extension-context (:vscode/extension-context (config-of server-name))}
            nil))
    {:ok true}
    (catch :default e
      {:error/message (error-message e)})))

(defn- registry-stopped
  [_dispatch! server-name instance-slug]
  (registry-writer/on-stopped! (config-of server-name) {:server/instance-slug instance-slug}))

(defn- forget-server
  [_dispatch! server-name]
  (swap! !live-objects update server-name dissoc :live/server-info))

(defn- show-stopped-message
  [_dispatch! server-name]
  (dialog/show-stopped-message!+ (assoc (config-of server-name) :manual-setup/silent? false)))

(def effects
  {:lifecycle/fx.return #'return
   :lifecycle/fx.report-server-state #'report-server-state
   :lifecycle/fx.start-server #'start-server
   :lifecycle/fx.wait-until-ready #'wait-until-ready
   :lifecycle/fx.register-with-cursor #'register-with-cursor
   :lifecycle/fx.register-with-eca #'register-with-eca
   :lifecycle/fx.registry-started #'registry-started
   :lifecycle/fx.show-start-message #'show-start-message
   :lifecycle/fx.sweep-port-files #'sweep-port-files
   :lifecycle/fx.sweep-cursor-registrations #'sweep-cursor-registrations
   :lifecycle/fx.registry-stopping #'registry-stopping
   :lifecycle/fx.stop-server #'stop-server
   :lifecycle/fx.unregister-from-cursor #'unregister-from-cursor
   :lifecycle/fx.registry-stopped #'registry-stopped
   :lifecycle/fx.forget-server #'forget-server
   :lifecycle/fx.show-stopped-message #'show-stopped-message})
