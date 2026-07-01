(ns vscode-mcp.stdio-config-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.stdio-config :as sut]))

(deftest normalize-host-test
  (testing "blank host defaults to loopback"
    (is (= "127.0.0.1" (sut/normalize-host nil)))
    (is (= "127.0.0.1" (sut/normalize-host "")))
    (is (= "127.0.0.1" (sut/normalize-host "   "))))

  (testing "custom host is preserved"
    (is (= "0.0.0.0" (sut/normalize-host "0.0.0.0")))
    (is (= "localhost" (sut/normalize-host "localhost")))))

(deftest stdio-args-test
  (let [wrapper "/ext/dist/mcp-server.js"]
    (testing "port path with default host"
      (is (= [wrapper "1664" "127.0.0.1"]
             (sut/stdio-args wrapper "1664" nil))))

    (testing "port-file path with default host"
      (is (= [wrapper "/ws/.calva/mcp-server/port" "127.0.0.1"]
             (sut/stdio-args wrapper "/ws/.calva/mcp-server/port" ""))))

    (testing "custom host"
      (is (= [wrapper "/ws/port" "0.0.0.0"]
             (sut/stdio-args wrapper "/ws/port" "0.0.0.0"))))))

(deftest stdio-command-string-test
  (testing "full shell command shape"
    (is (= "node /ext/mcp.js 1664 127.0.0.1"
           (sut/stdio-command-string "node" "/ext/mcp.js" "1664" nil)))
    (is (= "node /ext/mcp.js /ws/port 192.168.1.10"
           (sut/stdio-command-string "node" "/ext/mcp.js" "/ws/port" "192.168.1.10")))))
