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
    (is (not (sut/should-auto-start? {:mcp/auto-start? false :mcp/auto-register? false :mcp/cursor-available? false}))))

  (testing "omitting ECA keys keeps Cursor behavior"
    (is (sut/should-auto-start? {:mcp/auto-start? false :mcp/auto-register? true :mcp/cursor-available? true}))
    (is (not (sut/should-auto-start? {:mcp/auto-start? false :mcp/auto-register? true :mcp/cursor-available? false}))))

  (testing "ECA auto-register when extension and workspace present"
    (is (sut/should-auto-start? {:mcp/auto-start? false
                                 :mcp/auto-register? false
                                 :mcp/cursor-available? false
                                 :mcp/auto-register-eca? true
                                 :mcp/eca-available? true
                                 :mcp/workspace-root-present? true})))

  (testing "ECA setting without workspace does not auto-start"
    (is (not (sut/should-auto-start? {:mcp/auto-start? false
                                      :mcp/auto-register? false
                                      :mcp/cursor-available? false
                                      :mcp/auto-register-eca? true
                                      :mcp/eca-available? true
                                      :mcp/workspace-root-present? false}))))

  (testing "ECA setting without extension does not auto-start"
    (is (not (sut/should-auto-start? {:mcp/auto-start? false
                                      :mcp/auto-register? false
                                      :mcp/cursor-available? false
                                      :mcp/auto-register-eca? true
                                      :mcp/eca-available? false
                                      :mcp/workspace-root-present? true})))))

(deftest should-register-with-cursor?-test
  (testing "all conditions met"
    (is (sut/should-register-with-cursor? {:mcp/auto-register? true :mcp/cursor-available? true :mcp/port-file-present? true})))

  (testing "setting off"
    (is (not (sut/should-register-with-cursor? {:mcp/auto-register? false :mcp/cursor-available? true :mcp/port-file-present? true}))))

  (testing "API unavailable"
    (is (not (sut/should-register-with-cursor? {:mcp/auto-register? true :mcp/cursor-available? false :mcp/port-file-present? true}))))

  (testing "missing port file"
    (is (not (sut/should-register-with-cursor? {:mcp/auto-register? true :mcp/cursor-available? true :mcp/port-file-present? false})))))

(deftest should-register-with-eca?-test
  (testing "all conditions met"
    (is (sut/should-register-with-eca? {:mcp/auto-register-eca? true
                                        :mcp/eca-available? true
                                        :mcp/port-file-present? true
                                        :mcp/workspace-root-present? true})))

  (testing "setting off"
    (is (not (sut/should-register-with-eca? {:mcp/auto-register-eca? false
                                             :mcp/eca-available? true
                                             :mcp/port-file-present? true
                                             :mcp/workspace-root-present? true}))))

  (testing "extension unavailable"
    (is (not (sut/should-register-with-eca? {:mcp/auto-register-eca? true
                                             :mcp/eca-available? false
                                             :mcp/port-file-present? true
                                             :mcp/workspace-root-present? true}))))

  (testing "missing port file"
    (is (not (sut/should-register-with-eca? {:mcp/auto-register-eca? true
                                             :mcp/eca-available? true
                                             :mcp/port-file-present? false
                                             :mcp/workspace-root-present? true}))))

  (testing "no workspace"
    (is (not (sut/should-register-with-eca? {:mcp/auto-register-eca? true
                                             :mcp/eca-available? true
                                             :mcp/port-file-present? true
                                             :mcp/workspace-root-present? false}))))

  (testing "omitting ECA keys does not register"
    (is (not (sut/should-register-with-eca? {:mcp/port-file-present? true})))))

(deftest should-register-on-start?-test
  (testing "all conditions met without skip"
    (is (sut/should-register-on-start? {:mcp/auto-register? true
                                        :mcp/cursor-available? true
                                        :mcp/port-file-present? true
                                        :lifecycle/skip-register? false})))

  (testing "skip-register prevents registration even when otherwise allowed"
    (is (not (sut/should-register-on-start? {:mcp/auto-register? true
                                              :mcp/cursor-available? true
                                              :mcp/port-file-present? true
                                              :lifecycle/skip-register? true}))))

  (testing "nil skip-register does not prevent registration"
    (is (sut/should-register-on-start? {:mcp/auto-register? true
                                          :mcp/cursor-available? true
                                          :mcp/port-file-present? true})))

  (testing "inherits should-register-with-cursor? guards"
    (is (not (sut/should-register-on-start? {:mcp/auto-register? false
                                              :mcp/cursor-available? true
                                              :mcp/port-file-present? true
                                              :lifecycle/skip-register? false})))))
