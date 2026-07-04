(ns vscode-mcp.server-readiness-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.server-readiness :as sut]))

(deftest attempt-decision-test
  (testing "retry within budget"
    (is (= {:readiness/action :retry
            :readiness/delay-ms 100
            :readiness/elapsed-ms 500}
           (sut/attempt-decision
            {:readiness/start-ms 0 :readiness/now-ms 500}
            nil))))

  (testing "give up at budget"
    (is (= {:readiness/action :give-up :readiness/elapsed-ms 5000}
           (sut/attempt-decision
            {:readiness/start-ms 0 :readiness/now-ms 5000}
            nil)))))
