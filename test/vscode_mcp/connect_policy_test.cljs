(ns vscode-mcp.connect-policy-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.stdio.connect-policy :as sut]))

(def base-timing
  {:connect/start-ms 1000
   :connect/now-ms 1000})

(deftest attempt-decision-retry-within-budget-test
  (testing "retry when elapsed is within timeout budget"
    (is (= {:connect/action :retry
            :connect/delay-ms 500
            :connect/elapsed-ms 1000}
           (sut/attempt-decision
            {:connect/start-ms 0 :connect/now-ms 1000}
            nil)))
    (is (= {:connect/action :retry
            :connect/delay-ms 500
            :connect/elapsed-ms 59999}
           (sut/attempt-decision
            {:connect/start-ms 0 :connect/now-ms 59999}
            nil)))))

(deftest attempt-decision-give-up-at-or-after-budget-test
  (testing "give up when elapsed reaches or exceeds timeout budget"
    (is (= {:connect/action :give-up
            :connect/elapsed-ms 60000}
           (sut/attempt-decision
            {:connect/start-ms 0 :connect/now-ms 60000}
            nil)))
    (is (= {:connect/action :give-up
            :connect/elapsed-ms 61000}
           (sut/attempt-decision
            {:connect/start-ms 0 :connect/now-ms 61000}
            nil)))))

(deftest attempt-decision-boundary-at-exactly-timeout-test
  (testing "boundary at exactly timeout-ms gives up, not retry"
    (is (= :give-up
           (:connect/action
            (sut/attempt-decision
             {:connect/start-ms 1000 :connect/now-ms 61000}
             {:connect/timeout-ms 60000
              :connect/interval-ms 500}))))))

(deftest attempt-decision-elapsed-zero-always-retries-test
  (testing "decision at elapsed 0 always retries"
    (let [decision (sut/attempt-decision base-timing nil)]
      (is (= :retry (:connect/action decision)))
      (is (= 0 (:connect/elapsed-ms decision)))
      (is (= 500 (:connect/delay-ms decision))))))
