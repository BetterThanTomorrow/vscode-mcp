(ns vscode-mcp.policy-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.policy :as sut]))

(deftest should-auto-start?-test
  (testing "auto-start alone"
    (is (sut/should-auto-start? {:mcp/auto-start? true :mcp/auto-register? false :mcp/cursor-available? false})))

  (testing "Cursor auto-register when API available"
    (is (sut/should-auto-start? {:mcp/auto-start? false :mcp/auto-register? true :mcp/cursor-available? true})))

  (testing "Cursor setting without API does not auto-start"
    (is (not (sut/should-auto-start? {:mcp/auto-start? false :mcp/auto-register? true :mcp/cursor-available? false}))))

  (testing "both off"
    (is (not (sut/should-auto-start? {:mcp/auto-start? false :mcp/auto-register? false :mcp/cursor-available? false})))))

(deftest should-register-with-cursor?-test
  (testing "all conditions met"
    (is (sut/should-register-with-cursor? {:mcp/auto-register? true :mcp/cursor-available? true :mcp/port-file-present? true})))

  (testing "setting off"
    (is (not (sut/should-register-with-cursor? {:mcp/auto-register? false :mcp/cursor-available? true :mcp/port-file-present? true}))))

  (testing "API unavailable"
    (is (not (sut/should-register-with-cursor? {:mcp/auto-register? true :mcp/cursor-available? false :mcp/port-file-present? true}))))

  (testing "missing port file"
    (is (not (sut/should-register-with-cursor? {:mcp/auto-register? true :mcp/cursor-available? true :mcp/port-file-present? false})))))
