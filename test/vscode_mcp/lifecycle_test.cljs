(ns vscode-mcp.lifecycle-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.lifecycle.pure :as sut]))

;; Targets vscode-mcp.lifecycle.pure directly (not vscode-mcp.lifecycle)
;; so this test file never has to load "vscode". The orchestration flows
;; (maybe-start!+, start!+, stop!+, cursor-mode?, and the manual-start
;; dialog) are verified live via the connected extension-host REPL instead —
;; see the plan's Phase 6.6b verification notes.

(deftest init-state-test
  (testing "zero-value state is not running and has no dedupe flags set"
    (let [state (sut/init-state)]
      (is (not (sut/running? state)))
      (is (nil? (sut/server-info state)))
      (is (false? (:lifecycle/cursor-registered? state)))
      (is (false? (:lifecycle/cursor-register-called? state))))))

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

;; --- Cursor dedupe truth table --------------------------------------------

(deftest should-call-register-server-test
  (testing "registers when allowed, not registered, not called, silent"
    (is (sut/should-call-register-server?
         {:lifecycle/cursor-registered? false :lifecycle/cursor-register-called? false}
         {:lifecycle/silent? true}
         true)))

  (testing "does not register when policy disallows it"
    (is (not (sut/should-call-register-server?
              {:lifecycle/cursor-registered? false :lifecycle/cursor-register-called? false}
              {:lifecycle/silent? true}
              false))))

  (testing "does not register when already registered"
    (is (not (sut/should-call-register-server?
              {:lifecycle/cursor-registered? true :lifecycle/cursor-register-called? true}
              {:lifecycle/silent? true}
              true))))

  (testing "silent start: does not re-register when already called this activation"
    (is (not (sut/should-call-register-server?
              {:lifecycle/cursor-registered? false :lifecycle/cursor-register-called? true}
              {:lifecycle/silent? true}
              true))))

  (testing "manual (non-silent) start: clears called-flag, allows re-register attempt"
    (is (sut/should-call-register-server?
         {:lifecycle/cursor-registered? false :lifecycle/cursor-register-called? true}
         {:lifecycle/silent? false}
         true)))

  (testing "manual start still refuses when already registered"
    (is (not (sut/should-call-register-server?
              {:lifecycle/cursor-registered? true :lifecycle/cursor-register-called? true}
              {:lifecycle/silent? false}
              true)))))
