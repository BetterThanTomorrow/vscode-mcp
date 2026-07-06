(ns vscode-mcp.server-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.server-info :as sut]))

(deftest merge-started-server-info-test
  (testing "keeps both request and assigned ports as distinct keys"
    (let [runtime {:server/request-port 0
                   :server/host "127.0.0.1"
                   :server/active-sockets (atom #{})}
          server-info {:server/assigned-port 54321
                       :server/instance #js {}}
          result (sut/merge-started-server-info runtime server-info
                                                {:server/host "127.0.0.1"})]
      (is (= 0 (:server/request-port result)))
      (is (= 54321 (:server/assigned-port result)))))

  (testing "includes port-file-uri when provided"
    (let [uri #js {:fsPath "/tmp/port"}
          result (sut/merge-started-server-info {:server/request-port 0}
                                                {:server/assigned-port 1664}
                                                {:server/host "localhost"
                                                 :server/port-file-uri uri})]
      (is (= 1664 (:server/assigned-port result)))
      (is (= uri (:server/port-file-uri result)))
      (is (= "localhost" (:server/host result))))))

(deftest discovery-request-test
  (testing "tools/list and resources/list count as discovery"
    (is (sut/discovery-request? "tools/list"))
    (is (sut/discovery-request? "resources/list")))
  (testing "other methods do not"
    (is (not (sut/discovery-request? "initialize")))
    (is (not (sut/discovery-request? "tools/call")))))
