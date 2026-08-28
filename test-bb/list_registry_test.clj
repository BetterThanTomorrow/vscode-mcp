(ns list-registry-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [list-registry :as listing]))

(deftest session-rel-root-test
  (testing "first folder wins over workspace file"
    (is (= "/a/first"
           (listing/session-rel-root {:workspaceFolder "/a/first"
                                      :workspaceRoot "/ws/app.code-workspace"}))))
  (testing "parent of workspace file when no folder"
    (is (= "/ws"
           (listing/session-rel-root {:workspaceRoot "/ws/app.code-workspace"}))))
  (testing "plain folder"
    (is (= "/a/proj"
           (listing/session-rel-root {:workspaceRoot "/a/proj"}))))
  (testing "Windows-style workspace file keeps backslash parent"
    (is (= "C:\\ws"
           (listing/session-rel-root {:workspaceRoot "C:\\ws\\app.code-workspace"})))))

(deftest format-window-text-test
  (testing "header is serverName windowId appId workspaceRoot"
    (let [text (listing/format-window-text
                {:serverName "calva-backseat-driver"
                 :windowId "ws-abc"
                 :appId "cursor"
                 :workspaceRoot "/ws/app.code-workspace"
                 :hostname "host"
                 :ageMs 3000})]
      (is (string/starts-with? text
                               "calva-backseat-driver  ws-abc  cursor  /ws/app.code-workspace"))))
  (testing "missing workspaceRoot prints no folder"
    (let [text (listing/format-window-text
                {:serverName "calva-backseat-driver"
                 :windowId "win-x"
                 :appId "cursor"
                 :hostname "host"
                 :ageMs 0})]
      (is (string/includes? (first (string/split-lines text)) "no folder")))))
