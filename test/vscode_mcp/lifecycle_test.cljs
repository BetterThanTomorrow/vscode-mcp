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

(deftest server-facts-test
  (testing "a server that doesn't run"
    (is (= {:server/running? false
            :server/host nil
            :server/assigned-port nil
            :server/instance-slug nil
            :server/port-file-path nil
            :lifecycle/registered-name nil}
           (sut/server-facts (sut/init-state)))))
  (testing "a running server, registered with Cursor"
    (is (= {:server/running? true
            :server/host "127.0.0.1"
            :server/assigned-port 49311
            :server/instance-slug "ws-bxnohh"
            :server/port-file-path "/home/.config/vscode-mcp/port-files/joyride-ws-bxnohh.port"
            :lifecycle/registered-name "joyride-ws-bxnohh-0"}
           (sut/server-facts (assoc (sut/init-state)
                                    :lifecycle/registered-name "joyride-ws-bxnohh-0"
                                    :lifecycle/server-info {:server/host "127.0.0.1"
                                                            :server/assigned-port 49311
                                                            :server/instance-slug "ws-bxnohh"
                                                            :server/instance #js {}
                                                            :server/port-file-uri #js {:fsPath "/home/.config/vscode-mcp/port-files/joyride-ws-bxnohh.port"}})))
        "The facts are plain data, without the server and the port file's Uri")))

(deftest server-state-changes-test
  (testing "the state of a server that doesn't run has every key"
    (is (= (set (keys sut/stopped-server-state))
           (set (concat (keys (sut/server-facts (sut/init-state)))
                        [:server/starting? :server/stopping? :server/error :lifecycle/registration-error])))))
  (testing "a change is put in the state"
    (is (= (assoc sut/stopped-server-state :server/stopping? true)
           (sut/changed-server-state sut/stopped-server-state {:server/stopping? true}))))
  (testing "a server that begins to start has no error from an earlier start"
    (is (= (assoc sut/stopped-server-state :server/starting? true)
           (sut/changed-server-state (assoc sut/stopped-server-state :server/error "EADDRINUSE")
                                     {:server/starting? true})))
    (is (= "EADDRINUSE"
           (:server/error (sut/changed-server-state (assoc sut/stopped-server-state :server/error "EADDRINUSE" :server/starting? true)
                                                    {:server/starting? false})))
        "and keeps the error of a start that just failed")))

(deftest registration-error-test
  (is (= {:reason :cursor-api-unavailable}
         (sut/registration-error {:ok false :reason :cursor-api-unavailable})))
  (is (= {:reason :registration-failed :message "boom"}
         (sut/registration-error {:ok false :error (js/Error. "boom") :config {}}))
      "A registration that threw has the reason for one that failed, and the error's message"))
