(ns vscode-mcp.lifecycle-axs-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.event-handler :as event-handler]
   [vscode-mcp.lifecycle :as lifecycle]
   [vscode-mcp.lifecycle-axs :as sut]))

(defn- handle
  "Returns what the lifecycle handlers give for `action` on `state`."
  [state action]
  (event-handler/handle-actions sut/actions state {:system/now 0} [action]))

(def ^:private facts
  "A start in VS Code, without ECA: the config's data and what the entry point gathered."
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

(def ^:private started
  "What the server start resolves to."
  {:server/host "127.0.0.1"
   :server/assigned-port 49311
   :server/port-file-path "/home/.config/vscode-mcp/port-files/my-server-ws-abc.port"
   :server/port-note nil})

(def ^:private stopped lifecycle/stopped-server-state)

(def ^:private running
  (assoc stopped
         :server/running? true
         :server/host "127.0.0.1"
         :server/assigned-port 49311
         :server/instance-slug "ws-abc"
         :server/port-file-path "/home/.config/vscode-mcp/port-files/my-server-ws-abc.port"))

(defn- state-of
  "A state where `my-server` has `server-state`, reported and kept, at `generation`."
  ([server-state] (state-of server-state 0))
  ([server-state generation]
   {"my-server" (assoc server-state :lifecycle/generation generation)}))

(defn- reported [result]
  (sut/server-state (:uf/db result) "my-server"))

(deftest start
  (testing "a server that doesn't run"
    (let [result (handle {} [:lifecycle/ax.start facts])]
      (is (= (assoc stopped :server/starting? true) (reported result))
          "The server is starting")
      (is (= [[:uf/await :lifecycle/fx.start-server {:server/name "my-server"
                                                     :server/host "127.0.0.1"
                                                     :server/instance-slug "ws-abc"
                                                     :server/request-port 1664}]]
             (:uf/fxs result))
          "The server starts on the port the settings ask for")
      (is (= [[:lifecycle/ax.server-started facts :uf/prev-result]]
             (:uf/dxs result)))))
  (testing "a start after one that failed"
    (is (= (assoc stopped :server/starting? true)
           (reported (handle (state-of (assoc stopped
                                              :server/error "listen EADDRINUSE"
                                              :lifecycle/registration-error {:reason :registration-failed}))
                             [:lifecycle/ax.start facts])))
        "has neither the earlier start's error nor the earlier registration's"))
  (testing "in Cursor, with registration at start"
    (is (= 0 (-> (handle {} [:lifecycle/ax.start in-cursor])
                 :uf/fxs first (nth 2) :server/request-port))
        "The server listens on a port the system picks")
    (is (= 1664 (-> (handle {} [:lifecycle/ax.start (assoc in-cursor :mcp/auto-register? false)])
                    :uf/fxs first (nth 2) :server/request-port))
        "With auto-registration off it listens on the port the settings ask for"))
  (testing "a server that runs"
    (let [then [[:lifecycle/ax.register-started-server facts]]
          result (handle (state-of running) [:lifecycle/ax.start (assoc facts :lifecycle/then then)])]
      (is (= [[:uf/await :lifecycle/fx.return running]] (:uf/fxs result))
          "The start resolves to the server's state, and nothing starts")
      (is (= (state-of running) (:uf/db result)))
      (is (= then (:uf/dxs result))
          "What was to follow the start follows")))
  (testing "a server that is starting"
    (let [starting (assoc stopped :server/starting? true)
          result (handle (state-of starting) [:lifecycle/ax.start facts])]
      (is (= [[:uf/await :lifecycle/fx.return starting]] (:uf/fxs result))
          "A second start starts no second server")
      (is (= (state-of starting) (:uf/db result))))))

(deftest server-started
  (testing "a server that listens"
    (let [result (handle (state-of (assoc stopped :server/starting? true))
                         [:lifecycle/ax.server-started facts started])]
      (is (= [[:uf/await :lifecycle/fx.wait-until-ready "my-server"]] (:uf/fxs result))
          "The server is waited for until it takes connections")
      (is (= [[:lifecycle/ax.server-ready facts started]] (:uf/dxs result)))
      (is (false? (:server/running? (reported result)))
          "It is reported as running when it is ready")))
  (testing "a start that failed"
    (let [then [[:lifecycle/ax.register-started-server facts]]
          result (handle (state-of (assoc stopped :server/starting? true))
                         [:lifecycle/ax.server-started (assoc facts :lifecycle/then then)
                          {:error/message "listen EADDRINUSE: address already in use"}])
          failed (assoc stopped :server/error "listen EADDRINUSE: address already in use")]
      (is (= failed (reported result))
          "The state has the error, and no server")
      (is (= [[:uf/await :lifecycle/fx.return failed]] (:uf/fxs result))
          "The start resolves to that state, and nothing is torn down, since the start left nothing")
      (is (= then (:uf/dxs result))
          "What was to follow the start follows"))))

(deftest server-ready
  (testing "in VS Code"
    (let [result (handle (state-of (assoc stopped :server/starting? true))
                         [:lifecycle/ax.server-ready facts started])]
      (is (= (assoc running :server/starting? true) (reported result))
          "The server runs, where it listens, with its window's slug and its port file")
      (is (= {:server/workspace-root "/projects/mini"
              :server/workspace-folder "/projects/mini"
              :server/app-id "vscode"}
             (select-keys (get (:uf/db result) "my-server")
                          [:server/workspace-root :server/workspace-folder :server/app-id]))
          "What the registry entry reads is kept, and not reported")
      (is (empty? (:uf/fxs result))
          "Nothing is registered with Cursor")
      (is (= [[:lifecycle/ax.registered-at-start facts nil]] (:uf/dxs result)))))
  (testing "in Cursor"
    (let [result (handle (state-of (assoc stopped :server/starting? true))
                         [:lifecycle/ax.server-ready in-cursor started])
          intent {:register/unregister-name nil
                  :register/generation 0
                  :register/register-name "my-server-ws-abc-g0"}]
      (is (= [[:uf/await :lifecycle/fx.register-with-cursor "my-server" intent]] (:uf/fxs result))
          "The server is registered under its name for this generation")
      (is (= [[:lifecycle/ax.registered-at-start (merge in-cursor intent) :uf/prev-result]] (:uf/dxs result))
          "The registration's intent goes on with the facts")))
  (testing "in Cursor, after a stop of a registered server"
    (is (= "my-server-ws-abc-g1"
           (-> (handle (state-of (assoc stopped :server/starting? true) 1)
                       [:lifecycle/ax.server-ready in-cursor started])
               :uf/fxs first (nth 3) :register/register-name))
        "The name has the next generation"))
  (testing "in Cursor, for a start that skips the registration"
    (is (empty? (:uf/fxs (handle (state-of (assoc stopped :server/starting? true))
                                 [:lifecycle/ax.server-ready (assoc in-cursor :lifecycle/skip-register? true) started])))))
  (testing "in Cursor, with auto-registration off"
    (is (empty? (:uf/fxs (handle (state-of (assoc stopped :server/starting? true))
                                 [:lifecycle/ax.server-ready (assoc in-cursor :mcp/auto-register? false) started]))))))

(def ^:private intent
  {:register/unregister-name nil
   :register/generation 0
   :register/register-name "my-server-ws-abc-g0"})

(def ^:private registered
  (assoc running :lifecycle/registered-name "my-server-ws-abc-g0"))

(deftest registered-at-start
  (let [starting (state-of (assoc running :server/starting? true))]
    (testing "a start without a registration"
      (let [result (handle starting [:lifecycle/ax.registered-at-start facts nil])]
        (is (= [[:uf/await :lifecycle/fx.registry-started "my-server" (get starting "my-server")]]
               (:uf/fxs result))
            "The window's registry entry is written, from the server's data")
        (is (= [[:lifecycle/ax.start-finished facts]] (:uf/dxs result)))))
    (testing "a registration that Cursor took"
      (is (= (assoc registered :server/starting? true)
             (reported (handle starting [:lifecycle/ax.registered-at-start (merge in-cursor intent) {:ok true}])))
          "The server has its name in Cursor"))
    (testing "a registration that failed"
      (is (= (assoc running
                    :server/starting? true
                    :lifecycle/registration-error {:reason :registration-failed :message "boom"})
             (reported (handle starting [:lifecycle/ax.registered-at-start (merge in-cursor intent)
                                         {:ok false :reason :registration-failed :message "boom"}])))
          "The server runs, with the registration's error"))
    (testing "with ECA installed"
      (is (= [:uf/await :lifecycle/fx.register-with-eca "my-server"]
             (first (:uf/fxs (handle starting [:lifecycle/ax.registered-at-start
                                               (assoc facts :mcp/eca-available? true) nil]))))
          "ECA gets its entry, before the registry entry is written")
      (is (= 1 (count (:uf/fxs (handle starting [:lifecycle/ax.registered-at-start
                                                 (assoc facts
                                                        :mcp/eca-available? true
                                                        :mcp/workspace-root-present? false)
                                                 nil]))))
          "but not in a window without a folder"))))

(deftest start-finished
  (let [starting (state-of (assoc running :server/starting? true))]
    (testing "a start by a person"
      (let [result (handle starting [:lifecycle/ax.start-finished facts])]
        (is (= running (reported result))
            "The server no longer starts")
        (is (= [[:lifecycle/fx.show-start-message "my-server" (get (state-of running) "my-server")]
                [:uf/await :lifecycle/fx.return running]]
               (:uf/fxs result))
            "The person is shown how to connect, which isn't waited for, and the start resolves to the server's state")))
    (testing "a silent start"
      (is (= [[:uf/await :lifecycle/fx.return running]]
             (:uf/fxs (handle starting [:lifecycle/ax.start-finished (assoc facts :lifecycle/silent? true)])))
          "shows nothing"))
    (testing "a start with something to follow"
      (let [then [[:lifecycle/ax.register-started-server facts]]]
        (is (= then (:uf/dxs (handle starting [:lifecycle/ax.start-finished (assoc facts :lifecycle/then then)]))))))))

(deftest maybe-start
  (testing "nothing calls for a start"
    (let [result (handle {} [:lifecycle/ax.maybe-start facts])]
      (is (= [[:uf/await :lifecycle/fx.sweep-port-files "my-server"]
              [:uf/await :lifecycle/fx.return stopped]]
             (:uf/fxs result))
          "The port files of windows that are gone are swept, and it resolves to the stopped state")
      (is (empty? (:uf/dxs result)))))
  (testing "the setting asks for a start"
    (let [auto-start (assoc facts :mcp/auto-start? true)
          result (handle {} [:lifecycle/ax.maybe-start auto-start])]
      (is (= [[:uf/await :lifecycle/fx.sweep-port-files "my-server"]] (:uf/fxs result)))
      (is (= [[:lifecycle/ax.start (assoc auto-start :lifecycle/silent? true)]] (:uf/dxs result))
          "The start is silent")))
  (testing "in Cursor, with registration at start"
    (is (= [[:lifecycle/ax.start (assoc in-cursor :lifecycle/silent? true)]]
           (:uf/dxs (handle {} [:lifecycle/ax.maybe-start in-cursor])))
        "The server starts, so Cursor gets it"))
  (testing "in Cursor, with auto-registration off"
    (is (= [:uf/await :lifecycle/fx.sweep-cursor-registrations "my-server"]
           (second (:uf/fxs (handle {} [:lifecycle/ax.maybe-start (assoc in-cursor :mcp/auto-register? false)]))))
        "Registrations that earlier windows left are swept"))
  (testing "with ECA installed, in a window with a folder"
    (is (seq (:uf/dxs (handle {} [:lifecycle/ax.maybe-start (assoc facts :mcp/eca-available? true)])))))
  (testing "a server that runs"
    (is (seq (:uf/dxs (handle (state-of running) [:lifecycle/ax.maybe-start facts])))
        "goes through the start, which resolves to its state")))

(deftest stop
  (testing "no server runs"
    (let [result (handle {} [:lifecycle/ax.stop facts])]
      (is (= [[:uf/await :lifecycle/fx.return stopped]] (:uf/fxs result)))
      (is (empty? (:uf/dxs result)))))
  (testing "a running server"
    (let [result (handle (state-of running) [:lifecycle/ax.stop facts])]
      (is (= (assoc running :server/stopping? true) (reported result)))
      (is (= [[:lifecycle/fx.registry-stopping "my-server"]
              [:uf/await :lifecycle/fx.stop-server "my-server"]]
             (:uf/fxs result))
          "The registry entry's timers are cleared before the server stops")
      (is (= [[:lifecycle/ax.stopped facts :uf/prev-result]] (:uf/dxs result)))))
  (testing "a server that is registered with Cursor"
    (is (= [:uf/await :lifecycle/fx.unregister-from-cursor "my-server" "my-server-ws-abc-g0"]
           (last (:uf/fxs (handle (state-of registered) [:lifecycle/ax.stop facts]))))
        "is taken from Cursor after it stopped"))
  (testing "a server that listens, and whose start isn't finished"
    (let [starting (assoc running :server/starting? true)
          result (handle (state-of starting) [:lifecycle/ax.stop facts])]
      (is (= [[:uf/await :lifecycle/fx.return starting]] (:uf/fxs result))
          "is left to start: the stop resolves to its state, and stops nothing")
      (is (empty? (:uf/dxs result))))))

(deftest stopped-server
  (testing "a stop of a server that wasn't registered"
    (let [result (handle (state-of (assoc running :server/stopping? true) 2)
                         [:lifecycle/ax.stopped facts true])]
      (is (= (state-of stopped 2) (:uf/db result))
          "The state is the stopped state, at the same generation")
      (is (= [[:lifecycle/fx.registry-stopped "my-server" "ws-abc"]
              [:lifecycle/fx.forget-server "my-server"]
              [:lifecycle/fx.show-stopped-message "my-server"]
              [:uf/await :lifecycle/fx.return stopped]]
             (:uf/fxs result))
          "The registry entry is removed, the person is told, and the stop resolves to the stopped state")))
  (testing "a stop of a registered server"
    (is (= (state-of stopped 3)
           (:uf/db (handle (state-of (assoc registered :server/stopping? true) 2)
                           [:lifecycle/ax.stopped facts true])))
        "The next registration gets the next generation"))
  (testing "a silent stop"
    (is (not-any? #(= :lifecycle/fx.show-stopped-message (first %))
                  (:uf/fxs (handle (state-of (assoc running :server/stopping? true))
                                   [:lifecycle/ax.stopped (assoc facts :lifecycle/silent? true) true])))))
  (testing "a stop where a step failed"
    (let [result (handle (state-of (assoc registered :server/stopping? true) 2)
                         [:lifecycle/ax.stopped facts {:error/message "unregister failed"}])]
      (is (= (state-of stopped 0) (:uf/db result))
          "The state is the stopped state, and the generation starts over")
      (is (= [[:lifecycle/fx.registry-stopped "my-server" "ws-abc"]
              [:lifecycle/fx.forget-server "my-server"]
              [:uf/await :lifecycle/fx.return stopped]]
             (:uf/fxs result))
          "The registry entry is removed, and the person isn't told that the server stopped"))))

(deftest register-with-cursor
  (testing "in an editor without Cursor's registration API"
    (let [earlier (assoc running :lifecycle/registration-error {:reason :cursor-api-unavailable})
          result (handle (state-of earlier) [:lifecycle/ax.register-with-cursor facts])]
      (is (= running (reported result))
          "The earlier registration's error is cleared, as a change of its own")
      (is (empty? (:uf/fxs result))
          "Nothing starts, and Cursor isn't asked")
      (is (= [[:lifecycle/ax.registration-failed facts {:reason :cursor-api-unavailable}]] (:uf/dxs result))
          "The failure follows, so one that is the same as the one before is reported again")))
  (testing "a running server"
    (let [result (handle (state-of (assoc running :lifecycle/registration-error {:reason :registration-failed}))
                         [:lifecycle/ax.register-with-cursor in-cursor])]
      (is (nil? (:lifecycle/registration-error (reported result)))
          "The earlier registration's error is cleared")
      (is (= [[:uf/await :lifecycle/fx.register-with-cursor "my-server" intent]] (:uf/fxs result)))
      (is (= [[:lifecycle/ax.registered-on-request (merge in-cursor intent) :uf/prev-result]] (:uf/dxs result)))))
  (testing "a server that is registered already"
    (is (= {:register/unregister-name "my-server-ws-abc-g0"
            :register/generation 1
            :register/register-name "my-server-ws-abc-g1"}
           (-> (handle (state-of registered) [:lifecycle/ax.register-with-cursor in-cursor])
               :uf/fxs first (nth 3)))
        "gets the next generation's name, and the current name is taken from Cursor"))
  (testing "a window whose slug the facts give as another"
    (is (= "my-server-ws-abc-g0"
           (-> (handle (state-of running) [:lifecycle/ax.register-with-cursor (assoc in-cursor :server/instance-slug "ws-other")])
               :uf/fxs first (nth 3) :register/register-name))
        "The name has the slug the server started with"))
  (testing "no server runs"
    (let [result (handle {} [:lifecycle/ax.register-with-cursor in-cursor])]
      (is (empty? (:uf/fxs result)))
      (is (= [[:lifecycle/ax.start (assoc in-cursor
                                          :lifecycle/silent? true
                                          :lifecycle/skip-register? true
                                          :lifecycle/then [[:lifecycle/ax.register-started-server in-cursor]])]]
             (:uf/dxs result))
          "The server starts silently, without the registration a start makes, and the registration follows"))))

(deftest register-started-server
  (testing "the start gave a server"
    (is (= [[:uf/await :lifecycle/fx.register-with-cursor "my-server" intent]]
           (:uf/fxs (handle (state-of running) [:lifecycle/ax.register-started-server in-cursor])))))
  (testing "the start failed"
    (is (= [[:lifecycle/ax.registration-failed in-cursor {:reason :registration-failed}]]
           (:uf/dxs (handle (state-of (assoc stopped :server/error "listen EADDRINUSE"))
                            [:lifecycle/ax.register-started-server in-cursor]))))))

(deftest registration-failed
  (let [state (state-of (assoc stopped :server/error "listen EADDRINUSE"))
        failed (assoc stopped
                      :server/error "listen EADDRINUSE"
                      :lifecycle/registration-error {:reason :registration-failed})
        result (handle state [:lifecycle/ax.registration-failed in-cursor {:reason :registration-failed}])]
    (is (= failed (reported result))
        "The state has the registration's error")
    (is (= [[:uf/await :lifecycle/fx.return failed]] (:uf/fxs result))
        "and the registration resolves to it")))

(deftest registered-on-request
  (testing "a registration that Cursor took"
    (let [result (handle (state-of running) [:lifecycle/ax.registered-on-request (merge in-cursor intent) {:ok true}])]
      (is (= registered (reported result)))
      (is (= [[:uf/await :lifecycle/fx.return registered]] (:uf/fxs result)))))
  (testing "a registration of a server that was registered already"
    (let [next-intent {:register/unregister-name "my-server-ws-abc-g0"
                       :register/generation 1
                       :register/register-name "my-server-ws-abc-g1"}
          result (handle (state-of registered) [:lifecycle/ax.registered-on-request (merge in-cursor next-intent) {:ok true}])]
      (is (= "my-server-ws-abc-g1" (:lifecycle/registered-name (reported result))))
      (is (= 1 (get-in result [:uf/db "my-server" :lifecycle/generation]))
          "The generation of the new name is kept, so a later start gets the one after it")))
  (testing "a registration that failed"
    (is (= (assoc running :lifecycle/registration-error {:reason :registration-failed})
           (reported (handle (state-of running)
                             [:lifecycle/ax.registered-on-request (merge in-cursor intent) {:ok false :reason :registration-failed}])))
        "The server that runs is kept")))

(deftest state-effects
  (is (empty? (sut/state-effects (state-of running 0) (state-of running 1)))
      "A change in what the library keeps for itself is not reported")
  (is (= [[:lifecycle/fx.report-server-state "my-server" registered]]
         (sut/state-effects (state-of running) (state-of registered)))
      "A change in the server's state is reported, as the state")
  (is (= [[:lifecycle/fx.report-server-state "my-server" (assoc stopped :server/starting? true)]]
         (sut/state-effects {} (state-of (assoc stopped :server/starting? true))))
      "A server's first state is reported"))

(deftest every-action-a-flow-names-is-in-the-table
  (let [named (->> [(handle {} [:lifecycle/ax.start facts])
                    (handle {} [:lifecycle/ax.maybe-start (assoc facts :mcp/auto-start? true)])
                    (handle (state-of running) [:lifecycle/ax.server-started facts started])
                    (handle (state-of running) [:lifecycle/ax.server-ready in-cursor started])
                    (handle (state-of running) [:lifecycle/ax.registered-at-start facts nil])
                    (handle (state-of running) [:lifecycle/ax.stop facts])
                    (handle (state-of running) [:lifecycle/ax.register-with-cursor in-cursor])
                    (handle {} [:lifecycle/ax.register-with-cursor in-cursor])
                    (handle {} [:lifecycle/ax.register-with-cursor facts])
                    (handle {} [:lifecycle/ax.register-started-server in-cursor])]
                   (mapcat :uf/dxs)
                   (map first)
                   set)]
    (is (every? sut/actions named)
        (str "Deferred actions: " (pr-str named)))))
