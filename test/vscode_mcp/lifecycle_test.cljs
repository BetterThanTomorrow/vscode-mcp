(ns vscode-mcp.lifecycle-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.lifecycle :as sut]
   [vscode-mcp.lifecycle-turns :as lifecycle-turns]))

(deftest create-config-test
  (testing "defaults auto-start?, auto-register?, auto-register-eca?, host, and registry"
    (let [config (sut/create-config {:server/name "test"})]
      (is (false? (:mcp/auto-start? config)))
      (is (true? (:mcp/auto-register? config)))
      (is (false? (:mcp/auto-register-eca? config)))
      (is (= "127.0.0.1" (:server/host config)))
      (is (false? (:registry/enabled? config)))
      (is (= 30000 (:registry/heartbeat-ms config)))
      (is (= 1000 (:registry/debounce-ms config)))))

  (testing "explicit opts override defaults"
    (let [config (sut/create-config {:mcp/auto-start? true
                                     :mcp/auto-register-eca? true
                                     :server/host "0.0.0.0"
                                     :registry/enabled? true
                                     :registry/debounce-ms 200})]
      (is (true? (:mcp/auto-start? config)))
      (is (true? (:mcp/auto-register-eca? config)))
      (is (= "0.0.0.0" (:server/host config)))
      (is (true? (:registry/enabled? config)))
      (is (= 200 (:registry/debounce-ms config))))))

(deftest registration-intent-test
  (testing "fresh state registers at generation 0"
    (is (= {:register/unregister-name nil
            :register/generation 0
            :register/register-name "joyride-ws-abc-g0"}
           (sut/registration-intent {} "joyride" "ws-abc"))))

  (testing "already registered retires old name and bumps generation"
    (let [state (assoc {}
                       :lifecycle/registered-name "joyride-ws-abc-g0"
                       :lifecycle/generation 0)]
      (is (= {:register/unregister-name "joyride-ws-abc-g0"
              :register/generation 1
              :register/register-name "joyride-ws-abc-g1"}
             (sut/registration-intent state "joyride" "ws-abc")))))

  (testing "successive repairs produce strictly increasing generations"
    (let [state (assoc {}
                       :lifecycle/registered-name "joyride-ws-abc-g2"
                       :lifecycle/generation 2)
          intent (sut/registration-intent state "joyride" "ws-abc")]
      (is (= "joyride-ws-abc-g2" (:register/unregister-name intent)))
      (is (= 3 (:register/generation intent)))
      (is (= "joyride-ws-abc-g3" (:register/register-name intent))))))

(deftest registration-error-test
  (is (= {:reason :cursor-api-unavailable}
         (sut/registration-error {:ok false :reason :cursor-api-unavailable})))
  (is (= {:reason :registration-failed :message "boom"}
         (sut/registration-error {:ok false :message "boom"}))
      "A registration that threw has the reason for one that failed, and the error's message"))

(deftest turn-options-test
  (let [config {:server/name "my-server"}
        picked (fn [options]
                 (select-keys options [:turns/wait-ms :turns/from-call?]))]
    (is (= {:turns/wait-ms lifecycle-turns/default-wait-ms :turns/from-call? false}
           (picked (sut/turn-options config)))
        "A call waits for the one before it the default time, from when that one began")
    (is (= {:turns/wait-ms lifecycle-turns/default-wait-ms :turns/from-call? false}
           (picked (sut/turn-options config (sut/stop-options nil))))
        "and so does a stop with a message")
    (doseq [given [true {:lifecycle/silent? true}]]
      (is (= {:turns/wait-ms sut/silent-stop-wait-ms :turns/from-call? true}
             (picked (sut/turn-options config (sut/stop-options given))))
          (str "A silent stop, given " (pr-str given) ", waits its own limit, from when it is called")))
    (is (< sut/silent-stop-wait-ms 5000)
        "The silent stop's limit is shorter than the time VS Code gives deactivation")))

(deftest stop-options-test
  (is (= {:lifecycle/silent? false} (sut/stop-options nil)))
  (is (= {:lifecycle/silent? false} (sut/stop-options {})))
  (is (= {:lifecycle/silent? true} (sut/stop-options true)))
  (is (= {:lifecycle/silent? true} (sut/stop-options {:lifecycle/silent? true}))))

(deftest turn-options-log-a-waited-out-call
  (let [!logged (atom [])
        {:turns/keys [on-waited-out]} (sut/turn-options {:server/name "my-server"
                                                         :mcp/on-log (fn [& args]
                                                                       (swap! !logged conj args))})]
    (on-waited-out)
    (is (= [[:warn "[MCP] A lifecycle call for" "my-server" "hasn't settled after"
             lifecycle-turns/default-wait-ms "ms; the next one runs anyway"]]
           @!logged))))
