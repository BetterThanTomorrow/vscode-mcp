(ns vscode-mcp.wrapper-install-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.wrapper-install :as sut]))

(deftest installed-path-test
  (testing "joins install dir with script basename"
    (is (= "/home/x/.config/ext/foo-mcp-server.js"
           (sut/installed-path "/home/x/.config/ext" "dist/foo-mcp-server.js")))))
