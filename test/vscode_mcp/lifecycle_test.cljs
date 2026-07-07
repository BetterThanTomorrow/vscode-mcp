(ns vscode-mcp.lifecycle-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.lifecycle :as sut]))

(deftest init-state-test
  (testing "zero-value state"
    (let [state (sut/init-state)]
      (is (not (sut/running? state)))
      (is (nil? (sut/server-info state)))
      (is (nil? (:lifecycle/registered-name state)))
      (is (= 0 (:lifecycle/generation state)))
      (is (not (sut/cursor-registered? state))))))

(deftest running-and-server-info-test
  (testing "running? and server-info reflect :lifecycle/server-info"
    (let [state (assoc (sut/init-state) :lifecycle/server-info {:server/assigned-port 1664})]
      (is (sut/running? state))
      (is (= {:server/assigned-port 1664} (sut/server-info state))))))

(deftest create-config-test
  (testing "defaults auto-start?, auto-register?, and host"
    (let [config (sut/create-config {:cursor/server-name "test"})]
      (is (false? (:mcp/auto-start? config)))
      (is (true? (:mcp/auto-register? config)))
      (is (= "127.0.0.1" (:server/host config)))))

  (testing "explicit opts override defaults"
    (let [config (sut/create-config {:mcp/auto-start? true :server/host "0.0.0.0"})]
      (is (true? (:mcp/auto-start? config)))
      (is (= "0.0.0.0" (:server/host config))))))

(deftest port-file-present-test
  (testing "false when no port-file-uri"
    (is (not (sut/port-file-present? {}))))

  (testing "true when port-file-uri has a non-blank fsPath"
    (is (sut/port-file-present? {:server/port-file-uri #js {:fsPath "/ws/port"}}))))

(deftest registration-intent-test
  (testing "fresh state registers at generation 0"
    (is (= {:register/unregister-name nil
            :register/generation 0
            :register/register-name "joyride-ws-abc-g0"}
           (sut/registration-intent (sut/init-state) "joyride" "ws-abc"))))

  (testing "already registered retires old name and bumps generation"
    (let [state (assoc (sut/init-state)
                       :lifecycle/registered-name "joyride-ws-abc-g0"
                       :lifecycle/generation 0)]
      (is (= {:register/unregister-name "joyride-ws-abc-g0"
              :register/generation 1
              :register/register-name "joyride-ws-abc-g1"}
             (sut/registration-intent state "joyride" "ws-abc")))))

  (testing "successive repairs produce strictly increasing generations"
    (let [state (assoc (sut/init-state)
                       :lifecycle/registered-name "joyride-ws-abc-g2"
                       :lifecycle/generation 2)
          intent (sut/registration-intent state "joyride" "ws-abc")]
      (is (= "joyride-ws-abc-g2" (:register/unregister-name intent)))
      (is (= 3 (:register/generation intent)))
      (is (= "joyride-ws-abc-g3" (:register/register-name intent))))))
