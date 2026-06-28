(ns vscode-mcp.responses-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [pez.baldr]
   [vscode-mcp.responses :as sut]))

(deftest text-response-test
  (testing "returns standard MCP response format for raw text"
    (is (= {:jsonrpc "2.0"
            :id 42
            :result {:content [{:type "text"
                                :text "\"Hello World\""}]}}
           (sut/text-response 42 "Hello World")))))

(deftest clj-response-test
  (testing "returns JSON-stringified clj data"
    (let [res (sut/clj-response 1 {:foo "bar"})
          content (get-in res [:result :content 0 :text])]
      (is (= "{\"foo\":\"bar\"}" content))
      (is (= {:jsonrpc "2.0"
              :id 1
              :result {:content [{:type "text"
                                  :text "{\"foo\":\"bar\"}"}]}}
             res)))))

(deftest content-response-test
  (testing "returns pre-built content array"
    (is (= {:jsonrpc "2.0"
            :id 2
            :result {:content [{:type "image" :data "..."}]}}
           (sut/content-response 2 [{:type "image" :data "..."}])))))

(deftest error-response-test
  (testing "returns JSON-RPC error format"
    (is (= {:jsonrpc "2.0"
            :id 3
            :error {:code -32601
                    :message "Method not found"}}
           (sut/error-response 3 -32601 "Method not found")))))
