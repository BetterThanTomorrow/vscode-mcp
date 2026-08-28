(ns vscode-mcp.cursor-config-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.cursor-config :as sut]))

(deftest app-id-test
  (testing "prefers product applicationName"
    (is (= "code" (sut/app-id "code" "vscode"))))
  (testing "falls back to uriScheme"
    (is (= "vscode-insiders" (sut/app-id nil "vscode-insiders"))))
  (testing "nil when both missing"
    (is (nil? (sut/app-id nil nil)))))

(deftest instance-slug-test
  (testing "workspace path yields deterministic ws- slug"
    (is (re-matches #"ws-[a-z0-9]+"
                    (sut/instance-slug #:instance{:workspace-root-path "/Users/dev/my project (v2)!"})))
    (is (= (sut/instance-slug #:instance{:workspace-root-path "/a/b"})
           (sut/instance-slug #:instance{:workspace-root-path "/a/b"}))
        "deterministic for the same path")
    (is (not= (sut/instance-slug #:instance{:workspace-root-path "/a/b"})
              (sut/instance-slug #:instance{:workspace-root-path "/c/b"}))
        "differs for different paths"))

  (testing "host pid yields win- slug harmonized per extension host"
    (is (re-matches #"win-[a-z0-9]+"
                    (sut/instance-slug #:instance{:host-pid 12345})))
    (is (= (sut/instance-slug #:instance{:host-pid 12345})
           (sut/instance-slug #:instance{:host-pid 12345}))
        "same pid yields equal slugs")
    (is (not= (sut/instance-slug #:instance{:host-pid 12345})
              (sut/instance-slug #:instance{:host-pid 99999}))
        "different pids yield different slugs"))

  (testing "no workspace uses win- slug from process pid"
    (is (re-matches #"win-[a-z0-9]+" (sut/instance-slug {})))
    (is (= (sut/instance-slug {})
           (sut/instance-slug {}))
        "deterministic when called twice")))

(deftest instance-slug-app-and-workspace-file-test
  (testing "editor app distinguishes otherwise identical folders"
    (is (not= (sut/instance-slug #:instance{:app-id "cursor" :workspace-root-path "/a/b"})
              (sut/instance-slug #:instance{:app-id "vscode" :workspace-root-path "/a/b"}))))

  (testing "workspace file distinguishes a .code-workspace from a plain folder"
    (is (not= (sut/instance-slug #:instance{:app-id "cursor" :workspace-file-path "/ws/project.code-workspace" :workspace-root-path "/a/b"})
              (sut/instance-slug #:instance{:app-id "cursor" :workspace-root-path "/a/b"}))))

  (testing "workspace file wins over folder path"
    (is (= (sut/instance-slug #:instance{:app-id "cursor" :workspace-file-path "/ws/project.code-workspace" :workspace-root-path "/a/b"})
           (sut/instance-slug #:instance{:app-id "cursor" :workspace-file-path "/ws/project.code-workspace" :workspace-root-path "/other"}))))

  (testing "empty window includes app in the pid hash"
    (is (not= (sut/instance-slug #:instance{:app-id "cursor" :host-pid 12345})
              (sut/instance-slug #:instance{:app-id "vscode" :host-pid 12345})))
    (is (= (sut/instance-slug #:instance{:app-id "cursor" :host-pid 12345})
           (sut/instance-slug #:instance{:app-id "cursor" :host-pid 12345})))))

(deftest slugged-server-name-test
  (testing "always includes generation suffix"
    (is (= "joyride-ws-2ypyqk-g0"
           (sut/slugged-server-name "joyride" "ws-2ypyqk" 0)))
    (is (= "joyride-ws-2ypyqk-g2"
           (sut/slugged-server-name "joyride" "ws-2ypyqk" 2)))))

(deftest mcp-client-identifier-test
  (testing "builds identifier from extension id and server name"
    (let [ctx #js {:extension #js {:id "betterthantomorrow.calva-backseat-driver"}}]
      (is (= "user-betterthantomorrow.calva-backseat-driver-extension-joyride-ws-abc-g0"
             (sut/mcp-client-identifier {:vscode/extension-context ctx
                                         :cursor/server-name "joyride-ws-abc-g0"})))))

  (testing "nil without extension context"
    (is (nil? (sut/mcp-client-identifier {:cursor/server-name "joyride-ws-abc-g0"}))))

  (testing "nil without extension id"
    (is (nil? (sut/mcp-client-identifier {:vscode/extension-context #js {:extension #js {}}
                                          :cursor/server-name "joyride-ws-abc-g0"})))))
