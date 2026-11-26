(ns vscode-mcp.lifecycle-axs
  "What the library decides about a server: its start, its stop and its registration
   with Cursor, as actions for the loop in `vscode-mcp.event-handler`.

   The state is a map from server name to the server's entry: the state that the
   library reports for it, and beside that what the library keeps for itself, such as
   `:lifecycle/generation`. An action's `facts` are the config's data and what the entry
   point gathered from outside, such as whether Cursor's registration API is there."
  (:require
   [vscode-mcp.lifecycle :as lifecycle]
   [vscode-mcp.policy :as policy]))

(def ^:private stopped-entry
  (assoc lifecycle/stopped-server-state :lifecycle/generation 0))

(defn- entry
  [state server-name]
  (get state server-name stopped-entry))

(defn server-state
  "Returns the state of the server `server-name` as the library reports it."
  [state server-name]
  (select-keys (entry state server-name) (keys lifecycle/stopped-server-state)))

(defn state-effects
  "Returns the effects that a change from `old-state` to `new-state` calls for: each
   server whose reported state changed is reported."
  [old-state new-state]
  (for [server-name (keys new-state)
        :let [reported (server-state new-state server-name)]
        :when (not= reported (server-state old-state server-name))]
    [:lifecycle/fx.report-server-state server-name reported]))

(defn- returning
  "Returns the effect that resolves the dispatch to the reported state of `server-name` in `state`."
  [state server-name]
  [:uf/await :lifecycle/fx.return (server-state state server-name)])

(defn- registers-with-cursor-at-start?
  [{:mcp/keys [auto-register? cursor-available?]
    :lifecycle/keys [skip-register?]}]
  (boolean (policy/should-register-on-start? {:mcp/auto-register? auto-register?
                                              :mcp/cursor-available? cursor-available?
                                              :mcp/port-file-present? true
                                              :lifecycle/skip-register? skip-register?})))

(defn- start
  "Starts the server, unless it runs or is starting. A server that Cursor gets a
   registration of listens on a port the system picks."
  [state _uf-data {server-name :server/name
                   :server/keys [host request-port instance-slug]
                   :mcp/keys [auto-register? cursor-available?]
                   :as facts}]
  (if (some (entry state server-name) [:server/running? :server/starting?])
    {:uf/fxs [(returning state server-name)]
     :uf/dxs (:lifecycle/then facts)}
    {:uf/db (update state server-name #(assoc (or % stopped-entry)
                                              :server/starting? true
                                              :server/error nil
                                              :lifecycle/registration-error nil))
     :uf/fxs [[:uf/await :lifecycle/fx.start-server {:server/name server-name
                                                     :server/host host
                                                     :server/instance-slug instance-slug
                                                     :server/request-port (if (and cursor-available?
                                                                                   auto-register?)
                                                                            0
                                                                            request-port)}]]
     :uf/dxs [[:lifecycle/ax.server-started facts :uf/prev-result]]}))

(defn- server-started
  "Takes the result of the server start: what it listens on, or the error of a start
   that failed, which leaves no server."
  [state _uf-data {server-name :server/name :as facts} {:error/keys [message] :as started}]
  (if message
    (let [state (update state server-name assoc
                        :server/starting? false
                        :server/error message)]
      {:uf/db state
       :uf/fxs [(returning state server-name)]
       :uf/dxs (:lifecycle/then facts)})
    {:uf/fxs [[:uf/await :lifecycle/fx.wait-until-ready server-name]]
     :uf/dxs [[:lifecycle/ax.server-ready facts started]]}))

(defn- server-ready
  "The server runs. Registers it with Cursor when the settings and the editor call for it."
  [state _uf-data
   {server-name :server/name
    :server/keys [instance-slug workspace-root workspace-folder app-id]
    :as facts}
   {:server/keys [host assigned-port port-file-path port-note]}]
  (let [state (update state server-name assoc
                      :server/running? true
                      :server/host host
                      :server/assigned-port assigned-port
                      :server/port-file-path port-file-path
                      :server/port-note port-note
                      :server/instance-slug instance-slug
                      :server/workspace-root workspace-root
                      :server/workspace-folder workspace-folder
                      :server/app-id app-id)
        intent (when (registers-with-cursor-at-start? facts)
                 (lifecycle/registration-intent (entry state server-name) server-name instance-slug))]
    {:uf/db state
     :uf/fxs [(when intent
                [:uf/await :lifecycle/fx.register-with-cursor server-name intent])]
     :uf/dxs [[:lifecycle/ax.registered-at-start (merge facts intent) (when intent :uf/prev-result)]]}))

(defn- with-registration
  "Returns the server's `entry` after the Cursor registration that the `facts` have the
   intent of gave `result`: registered under the intent's name, or with the
   registration's error."
  [entry {:register/keys [register-name generation]} {:keys [ok] :as result}]
  (if ok
    (assoc entry
           :lifecycle/registered-name register-name
           :lifecycle/generation generation
           :lifecycle/registration-error nil)
    (assoc entry :lifecycle/registration-error (lifecycle/registration-error result))))

(defn- registered-at-start
  "Takes the result of the registration that a start made, when it made one: the `facts`
   then have its intent. Then ECA gets its entry, when the settings and what is
   installed call for it, and the window its registry entry."
  [state _uf-data
   {server-name :server/name
    :mcp/keys [auto-register-eca? eca-available? workspace-root-present?]
    :register/keys [register-name]
    :as facts}
   result]
  (let [state (cond-> state
                register-name (update server-name with-registration facts result))
        server (entry state server-name)]
    {:uf/db state
     :uf/fxs [(when (policy/should-register-with-eca?
                     {:mcp/auto-register-eca? auto-register-eca?
                      :mcp/eca-available? eca-available?
                      :mcp/port-file-present? (boolean (seq (:server/port-file-path server)))
                      :mcp/workspace-root-present? workspace-root-present?})
                [:uf/await :lifecycle/fx.register-with-eca server-name])
              [:uf/await :lifecycle/fx.registry-started server-name server]]
     :uf/dxs [[:lifecycle/ax.start-finished facts]]}))

(defn- start-finished
  "The start is done. The person is shown how to connect, unless the start is silent."
  [state _uf-data {server-name :server/name :lifecycle/keys [silent?] :as facts}]
  (let [state (update state server-name assoc :server/starting? false)]
    {:uf/db state
     :uf/fxs [(when-not silent?
                [:lifecycle/fx.show-start-message server-name (entry state server-name)])
              (returning state server-name)]
     :uf/dxs (:lifecycle/then facts)}))

(defn- maybe-start
  "Sweeps what earlier windows left behind, and starts the server when the settings and
   what is installed call for it, silently."
  [state _uf-data {server-name :server/name
                   :mcp/keys [auto-start? auto-register? cursor-available?
                              auto-register-eca? eca-available? workspace-root-present?]
                   :as facts}]
  (let [starts? (or (:server/running? (entry state server-name))
                    (policy/should-auto-start? {:mcp/auto-start? auto-start?
                                                :mcp/auto-register? auto-register?
                                                :mcp/cursor-available? cursor-available?
                                                :mcp/auto-register-eca? auto-register-eca?
                                                :mcp/eca-available? eca-available?
                                                :mcp/workspace-root-present? workspace-root-present?}))]
    {:uf/fxs [[:uf/await :lifecycle/fx.sweep-port-files server-name]
              (when (and cursor-available?
                         (not auto-register?))
                [:uf/await :lifecycle/fx.sweep-cursor-registrations server-name])
              (when-not starts?
                (returning state server-name))]
     :uf/dxs (when starts?
               [[:lifecycle/ax.start (assoc facts :lifecycle/silent? true)]])}))

(defn- stop
  "Stops the server, when it runs, and takes its registration from Cursor. A server
   that is still starting is left to start."
  [state _uf-data {server-name :server/name :as facts}]
  (let [{:server/keys [running? starting?] :lifecycle/keys [registered-name]} (entry state server-name)]
    (if (or (not running?)
            starting?)
      {:uf/fxs [(returning state server-name)]}
      {:uf/db (update state server-name assoc :server/stopping? true)
       :uf/fxs [[:lifecycle/fx.registry-stopping server-name]
                [:uf/await :lifecycle/fx.stop-server server-name]
                (when registered-name
                  [:uf/await :lifecycle/fx.unregister-from-cursor server-name registered-name])]
       :uf/dxs [[:lifecycle/ax.stopped facts :uf/prev-result]]})))

(defn- stopped
  "The server is stopped. A server that was registered gets a new name in Cursor the
   next time. When a step of the stop failed, the generation starts over, and the
   person isn't told that the server stopped."
  [state _uf-data {server-name :server/name :lifecycle/keys [silent?]} {:error/keys [message]}]
  (let [{:server/keys [instance-slug]
         :lifecycle/keys [registered-name generation]} (entry state server-name)
        state (assoc state server-name
                     (assoc stopped-entry :lifecycle/generation (cond
                                                                  message 0
                                                                  registered-name (inc generation)
                                                                  :else generation)))]
    {:uf/db state
     :uf/fxs [[:lifecycle/fx.registry-stopped server-name instance-slug]
              [:lifecycle/fx.forget-server server-name]
              (when-not (or silent?
                            message)
                [:lifecycle/fx.show-stopped-message server-name])
              (returning state server-name)]}))

(defn- register
  "Returns what registers the running server `server-name` with Cursor."
  [state {server-name :server/name :server/keys [instance-slug] :as facts}]
  (let [intent (lifecycle/registration-intent (entry state server-name)
                                              server-name
                                              (or (:server/instance-slug (entry state server-name))
                                                  instance-slug))]
    {:uf/fxs [[:uf/await :lifecycle/fx.register-with-cursor server-name intent]]
     :uf/dxs [[:lifecycle/ax.registered-on-request (merge facts intent) :uf/prev-result]]}))

(defn- registration-failed
  "A registration on request failed with `failure`, before Cursor was asked."
  [state _uf-data {server-name :server/name} failure]
  (let [state (update state server-name #(assoc (or % stopped-entry)
                                                :lifecycle/registration-error
                                                (lifecycle/registration-error failure)))]
    {:uf/db state
     :uf/fxs [(returning state server-name)]}))

(defn- register-with-cursor
  "Registers the server with Cursor on request, starting it first when it doesn't run,
   silently and without the registration a start makes. The earlier registration's
   error is cleared first, as a change of its own, so a registration that fails as the
   one before it did is reported again."
  [state _uf-data {server-name :server/name :mcp/keys [cursor-available?] :as facts}]
  (let [state (update state server-name #(assoc (or % stopped-entry)
                                                :lifecycle/registration-error nil))]
    (cond
      (not cursor-available?)
      {:uf/db state
       :uf/dxs [[:lifecycle/ax.registration-failed facts {:reason :cursor-api-unavailable}]]}

      (:server/running? (entry state server-name))
      (assoc (register state facts) :uf/db state)

      :else
      {:uf/db state
       :uf/dxs [[:lifecycle/ax.start (assoc facts
                                            :lifecycle/silent? true
                                            :lifecycle/skip-register? true
                                            :lifecycle/then [[:lifecycle/ax.register-started-server facts]])]]})))

(defn- register-started-server
  "Registers the server that a registration on request started, when the start gave one."
  [state _uf-data {server-name :server/name :as facts}]
  (if (:server/running? (entry state server-name))
    (register state facts)
    {:uf/dxs [[:lifecycle/ax.registration-failed facts {:reason :registration-failed}]]}))

(defn- registered-on-request
  "Takes the result of a registration on request, whose intent the `facts` have."
  [state _uf-data {server-name :server/name :as facts} result]
  (let [state (update state server-name with-registration facts result)]
    {:uf/db state
     :uf/fxs [(returning state server-name)]}))

(def actions
  {:lifecycle/ax.maybe-start #'maybe-start
   :lifecycle/ax.start #'start
   :lifecycle/ax.server-started #'server-started
   :lifecycle/ax.server-ready #'server-ready
   :lifecycle/ax.registered-at-start #'registered-at-start
   :lifecycle/ax.start-finished #'start-finished
   :lifecycle/ax.stop #'stop
   :lifecycle/ax.stopped #'stopped
   :lifecycle/ax.register-with-cursor #'register-with-cursor
   :lifecycle/ax.register-started-server #'register-started-server
   :lifecycle/ax.registration-failed #'registration-failed
   :lifecycle/ax.registered-on-request #'registered-on-request})
