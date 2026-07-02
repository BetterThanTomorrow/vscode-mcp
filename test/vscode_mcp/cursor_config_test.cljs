(ns vscode-mcp.cursor-config-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.cursor-config :as sut]))

(def sample-config
  {:name "test-server"
   :server {:command "node"
            :args ["/ext/dist/mcp-server.js" "/ws/.calva/mcp-server/port" "127.0.0.1"]
            :env {}}})

(deftest registration-config-changed?-test
  (testing "identical configs are not changed"
    (is (false? (sut/registration-config-changed? sample-config sample-config))))

  (testing "nil stored means first registration (changed)"
    (is (sut/registration-config-changed? nil sample-config)))

  (testing "changed wrapper path in :args is changed"
    (let [stored sample-config
          fresh (assoc-in sample-config [:server :args 0] "/other/wrapper.js")]
      (is (sut/registration-config-changed? stored fresh))))

  (testing "changed host in :args is changed"
    (let [stored sample-config
          fresh (assoc-in sample-config [:server :args 2] "0.0.0.0")]
      (is (sut/registration-config-changed? stored fresh))))

  (testing "JSON-round-tripped stored value compares equal to CLJ original"
    (let [stored (js->clj (clj->js sample-config))]
      (is (false? (sut/registration-config-changed? stored sample-config))))))
