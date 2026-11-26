(ns vscode-mcp.lifecycle-flows-test
  "Whole flows through the loop, with the lifecycle's actions and stand-ins for its
   effects: each stand-in records that it ran, and resolves to what the test gives it."
  (:require
   [cljs.test :refer [deftest is]]
   [vscode-mcp.event-handler :as event-handler]
   [vscode-mcp.lifecycle :as lifecycle]
   [vscode-mcp.lifecycle-axs :as lifecycle-axs]
   [vscode-mcp.lifecycle-turns :as lifecycle-turns]))

(def ^:private effect-names
  [:lifecycle/fx.return
   :lifecycle/fx.report-server-state
   :lifecycle/fx.start-server
   :lifecycle/fx.wait-until-ready
   :lifecycle/fx.register-with-cursor
   :lifecycle/fx.register-with-eca
   :lifecycle/fx.registry-started
   :lifecycle/fx.show-start-message
   :lifecycle/fx.sweep-port-files
   :lifecycle/fx.sweep-cursor-registrations
   :lifecycle/fx.registry-stopping
   :lifecycle/fx.stop-server
   :lifecycle/fx.unregister-from-cursor
   :lifecycle/fx.registry-stopped
   :lifecycle/fx.forget-server
   :lifecycle/fx.show-stopped-message])

(def ^:private started
  {:server/host "127.0.0.1"
   :server/assigned-port 49311
   :server/port-file-path "/home/.config/vscode-mcp/port-files/my-server-ws-abc.port"
   :server/port-note nil})

(defn- test-system
  "A system on its own state, where the effect named `k` resolves to `(get results k)`
   on a later tick, and `:lifecycle/fx.return` to its argument. `!performed` collects
   the names of the effects that ran, without the reports of the state, which
   `!reported` collects."
  [results]
  (let [!state (atom {})
        !performed (atom [])
        !reported (atom [])
        effect (fn [k]
                 (fn [_dispatch! & args]
                   (case k
                     :lifecycle/fx.report-server-state (do (swap! !reported conj (second args))
                                                           nil)
                     :lifecycle/fx.return (first args)
                     (do (swap! !performed conj k)
                         (js/Promise. (fn [resolve _reject]
                                        (js/setTimeout #(resolve (get results k)) 1)))))))
        system {:uf/!state !state
                :uf/action-table lifecycle-axs/actions
                :uf/effect-table (into {} (map (juxt identity effect)) effect-names)
                :uf/state-effects lifecycle-axs/state-effects}
        dispatch! (fn dispatch! [actions]
                    (event-handler/dispatch!+ (assoc system :uf/dispatch! dispatch!) actions))]
    {:dispatch! dispatch! :!state !state :!performed !performed :!reported !reported}))

(defn- dispatch+
  "Dispatches `action` through the system. Made as a call of its own, since an async
   function awaits the call of a local function."
  [{:keys [dispatch!]} action]
  (dispatch! [action]))

(def ^:private facts
  {:server/name "my-server"
   :server/host "127.0.0.1"
   :server/request-port 1664
   :server/instance-slug "ws-abc"
   :server/workspace-root "/projects/mini"
   :server/workspace-folder "/projects/mini"
   :server/app-id "vscode"
   :mcp/auto-start? false
   :mcp/auto-register? true
   :mcp/auto-register-eca? true
   :mcp/cursor-available? false
   :mcp/eca-available? false
   :mcp/workspace-root-present? true
   :lifecycle/silent? false})

(def ^:private in-cursor
  (assoc facts :mcp/cursor-available? true :server/app-id "cursor"))

(def ^:private stopped lifecycle/stopped-server-state)

(def ^:private running
  (assoc stopped
         :server/running? true
         :server/host "127.0.0.1"
         :server/assigned-port 49311
         :server/instance-slug "ws-abc"
         :server/port-file-path "/home/.config/vscode-mcp/port-files/my-server-ws-abc.port"))

(deftest ^:async a-start-in-vs-code
  (let [{:keys [!performed !reported] :as system} (test-system {:lifecycle/fx.start-server started
                                                                 :lifecycle/fx.wait-until-ready true})
        resolved (await (dispatch+ system [:lifecycle/ax.start facts]))]
    (is (= running resolved)
        "The start resolves to the running server's state")
    (is (= [:lifecycle/fx.start-server
            :lifecycle/fx.wait-until-ready
            :lifecycle/fx.registry-started
            :lifecycle/fx.show-start-message]
           @!performed)
        "The effects of a start, in order")
    (is (= [(assoc stopped :server/starting? true)
            (assoc running :server/starting? true)
            running]
           @!reported)
        "The state is reported as it changes: starting, running, started")))

(deftest ^:async a-start-in-cursor-with-eca
  (let [{:keys [!performed] :as system} (test-system {:lifecycle/fx.start-server started
                                                       :lifecycle/fx.wait-until-ready true
                                                       :lifecycle/fx.register-with-cursor {:ok true}})
        resolved (await (dispatch+ system [:lifecycle/ax.start (assoc in-cursor :mcp/eca-available? true)]))]
    (is (= (assoc running :lifecycle/registered-name "my-server-ws-abc-g0") resolved)
        "The server has its name in Cursor")
    (is (= [:lifecycle/fx.start-server
            :lifecycle/fx.wait-until-ready
            :lifecycle/fx.register-with-cursor
            :lifecycle/fx.register-with-eca
            :lifecycle/fx.registry-started
            :lifecycle/fx.show-start-message]
           @!performed)
        "Cursor is registered with when the server is ready, then ECA, then the registry entry is written")))

(deftest ^:async a-start-that-fails
  (let [{:keys [!performed !state] :as system} (test-system {:lifecycle/fx.start-server {:error/message "listen EADDRINUSE"}})
        failed (assoc stopped :server/error "listen EADDRINUSE")
        resolved (await (dispatch+ system [:lifecycle/ax.start in-cursor]))]
    (is (= failed resolved)
        "The start resolves to the state with the error")
    (is (= [:lifecycle/fx.start-server] @!performed)
        "Nothing is registered, written or shown, and nothing is torn down")
    (is (= failed (lifecycle-axs/server-state @!state "my-server")))
    (let [again (test-system {:lifecycle/fx.start-server started
                              :lifecycle/fx.wait-until-ready true})]
      (reset! (:!state again) @!state)
      (is (= running (await (dispatch+ again [:lifecycle/ax.start facts])))
          "A start after it starts one server, without the error"))))

(deftest ^:async a-registration-at-start-that-fails
  (let [{:keys [!performed] :as system} (test-system {:lifecycle/fx.start-server started
                                                       :lifecycle/fx.wait-until-ready true
                                                       :lifecycle/fx.register-with-cursor {:ok false
                                                                                           :reason :registration-failed
                                                                                           :message "boom"}})
        resolved (await (dispatch+ system [:lifecycle/ax.start in-cursor]))]
    (is (= (assoc running :lifecycle/registration-error {:reason :registration-failed :message "boom"})
           resolved)
        "The server runs, with the registration's error")
    (is (= :lifecycle/fx.registry-started (last (butlast @!performed)))
        "and the start goes on to the registry entry and the message")))

(deftest ^:async a-stop-of-a-registered-server-and-the-next-start
  (let [{:keys [!performed !state] :as system} (test-system {:lifecycle/fx.start-server started
                                                              :lifecycle/fx.wait-until-ready true
                                                              :lifecycle/fx.register-with-cursor {:ok true}
                                                              :lifecycle/fx.stop-server true
                                                              :lifecycle/fx.unregister-from-cursor {:ok true}})
        _ (await (dispatch+ system [:lifecycle/ax.start in-cursor]))
        _ (reset! !performed [])
        stopped-state (await (dispatch+ system [:lifecycle/ax.stop in-cursor]))]
    (is (= stopped stopped-state)
        "The stop resolves to the stopped state")
    (is (= [:lifecycle/fx.registry-stopping
            :lifecycle/fx.stop-server
            :lifecycle/fx.unregister-from-cursor
            :lifecycle/fx.registry-stopped
            :lifecycle/fx.forget-server
            :lifecycle/fx.show-stopped-message]
           @!performed)
        "The effects of a stop, in order")
    (is (= 1 (get-in @!state ["my-server" :lifecycle/generation])))
    (is (= "my-server-ws-abc-g1"
           (:lifecycle/registered-name (await (dispatch+ system [:lifecycle/ax.start in-cursor]))))
        "The next start registers under the next generation's name")
    (is (= "my-server-ws-abc-g2"
           (:lifecycle/registered-name (await (dispatch+ system [:lifecycle/ax.register-with-cursor in-cursor]))))
        "A registration on request of the registered server takes the generation after that")
    (await (dispatch+ system [:lifecycle/ax.stop in-cursor]))
    (is (= "my-server-ws-abc-g3"
           (:lifecycle/registered-name (await (dispatch+ system [:lifecycle/ax.start in-cursor]))))
        "and the start after a stop the one after that, so Cursor never sees a name twice")))

(deftest ^:async a-stop-where-the-unregistration-fails
  (let [{:keys [!performed !state] :as system} (test-system {:lifecycle/fx.start-server started
                                                              :lifecycle/fx.wait-until-ready true
                                                              :lifecycle/fx.register-with-cursor {:ok true}
                                                              :lifecycle/fx.stop-server true
                                                              :lifecycle/fx.unregister-from-cursor {:error/message "boom"}})
        _ (await (dispatch+ system [:lifecycle/ax.start in-cursor]))
        _ (reset! !performed [])
        stopped-state (await (dispatch+ system [:lifecycle/ax.stop in-cursor]))]
    (is (= stopped stopped-state))
    (is (not-any? #{:lifecycle/fx.show-stopped-message} @!performed)
        "The person isn't told that the server stopped")
    (is (some #{:lifecycle/fx.registry-stopped} @!performed)
        "The registry entry is removed all the same")
    (is (= 0 (get-in @!state ["my-server" :lifecycle/generation]))
        "The generation starts over")))

(deftest ^:async a-registration-on-request-with-no-server-running
  (let [{:keys [!performed] :as system} (test-system {:lifecycle/fx.start-server started
                                                       :lifecycle/fx.wait-until-ready true
                                                       :lifecycle/fx.register-with-cursor {:ok true}})
        resolved (await (dispatch+ system [:lifecycle/ax.register-with-cursor in-cursor]))]
    (is (= (assoc running :lifecycle/registered-name "my-server-ws-abc-g0") resolved)
        "It resolves to the state of the server it started, registered")
    (is (= [:lifecycle/fx.start-server
            :lifecycle/fx.wait-until-ready
            :lifecycle/fx.registry-started
            :lifecycle/fx.register-with-cursor]
           @!performed)
        "The start is silent and makes no registration of its own, and the registration follows it")))

(deftest ^:async a-registration-on-request-whose-start-fails
  (let [system (test-system {:lifecycle/fx.start-server {:error/message "listen EADDRINUSE"}})
        resolved (await (dispatch+ system [:lifecycle/ax.register-with-cursor in-cursor]))]
    (is (= (assoc stopped
                  :server/error "listen EADDRINUSE"
                  :lifecycle/registration-error {:reason :registration-failed})
           resolved)
        "It resolves to the state with the start's error and a failed registration")))

(deftest ^:async a-maybe-start
  (let [{:keys [!performed] :as system} (test-system {:lifecycle/fx.start-server started
                                                       :lifecycle/fx.wait-until-ready true})]
    (is (= stopped (await (dispatch+ system [:lifecycle/ax.maybe-start facts])))
        "With nothing that calls for a start, it resolves to the stopped state")
    (is (= [:lifecycle/fx.sweep-port-files] @!performed))
    (reset! !performed [])
    (is (= running (await (dispatch+ system [:lifecycle/ax.maybe-start (assoc facts :mcp/auto-start? true)])))
        "With the setting on, it resolves to the running server's state")
    (is (= [:lifecycle/fx.sweep-port-files
            :lifecycle/fx.start-server
            :lifecycle/fx.wait-until-ready
            :lifecycle/fx.registry-started]
           @!performed)
        "The port files are swept before the start, and the start shows no message")))

(deftest ^:async calls-in-turn-resolve-to-their-own-outcome
  (let [{:keys [!performed] :as system} (test-system {:lifecycle/fx.start-server started
                                                       :lifecycle/fx.wait-until-ready true
                                                       :lifecycle/fx.register-with-cursor {:ok true}
                                                       :lifecycle/fx.stop-server true
                                                       :lifecycle/fx.unregister-from-cursor {:ok true}})
        !in-progress (atom {})
        in-turn+ (fn [action]
                   (lifecycle-turns/in-turn+ !in-progress "my-server" (partial dispatch+ system action)))
        first-start (in-turn+ [:lifecycle/ax.start in-cursor])
        second-start (in-turn+ [:lifecycle/ax.start in-cursor])
        stop (in-turn+ [:lifecycle/ax.stop in-cursor])
        registered (assoc running :lifecycle/registered-name "my-server-ws-abc-g0")]
    (is (= registered (await first-start)))
    (is (= registered (await second-start))
        "A start that came in while the server started resolves to the running, registered server")
    (is (= stopped (await stop))
        "A stop that came in while the server started stops it")
    (is (= 1 (count (filter #{:lifecycle/fx.start-server} @!performed)))
        "One server is started")
    (is (some #{:lifecycle/fx.stop-server} @!performed))
    (is (= {} @!in-progress)
        "The calls are forgotten when they have settled")))

(deftest ^:async a-call-after-one-that-failed-runs
  (let [!in-progress (atom {})
        throwing (lifecycle-turns/in-turn+ !in-progress "my-server" (fn [] (throw (js/Error. "boom"))))
        rejecting (lifecycle-turns/in-turn+ !in-progress "my-server" (fn [] (js/Promise.reject (js/Error. "bang"))))
        next-call (lifecycle-turns/in-turn+ !in-progress "my-server" (constantly :ran))]
    (is (= "boom" (try (await throwing) (catch :default e (ex-message e))))
        "A call that throws rejects")
    (is (= "bang" (try (await rejecting) (catch :default e (ex-message e))))
        "and the call after it runs, and rejects")
    (is (= :ran (await next-call))
        "and the call after that runs")))
