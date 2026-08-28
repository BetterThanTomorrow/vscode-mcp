(ns vscode-mcp.registry-test
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.registry :as sut]))

(deftest entry-naming-test
  (testing "name and filename include server and window id"
    (is (= "backseat-driver-ws-1a2b3c" (sut/entry-name "backseat-driver" "ws-1a2b3c")))
    (is (= "joyride-win-abc.json" (sut/entry-filename "joyride" "win-abc")))))

(deftest registry-dir-test
  (testing "override wins"
    (is (= "/tmp/reg" (sut/registry-dir {:registry/dir "/tmp/reg"}))))
  (testing "default is under ~/.config/vscode-mcp/registry/windows"
    (is (= (path/join (os/homedir) ".config" "vscode-mcp" "registry" "windows")
           (sut/default-dir)))))

(deftest build-envelope-test
  (testing "schemaVersion, name, and serverName are present"
    (let [env (sut/build-envelope {:server-name "backseat-driver"
                                   :window-id "ws-1a2b3c"
                                   :hostname "Pappas-data"
                                   :pid 48291
                                   :updated-at "2026-08-25T09:30:00.000Z"})]
      (is (= 1 (:schemaVersion env)))
      (is (= "backseat-driver-ws-1a2b3c" (:name env)))
      (is (= "backseat-driver" (:serverName env)))
      (is (= "ws-1a2b3c" (:windowId env)))
      (is (= "Pappas-data" (:hostname env)))
      (is (= 48291 (:pid env)))
      (is (nil? (:workspaceRoot env)))
      (is (nil? (:appId env)))
      (is (nil? (:workspaceFolder env)))
      (is (nil? (:mcp env)))))
  (testing "workspaceRoot and mcp are included when provided"
    (let [mcp {:host "127.0.0.1" :port 1664}
          env (sut/build-envelope {:server-name "backseat-driver"
                                   :window-id "ws-1a2b3c"
                                   :workspace-root "/Users/pez/Projects/my-app"
                                   :hostname "host"
                                   :pid 1
                                   :updated-at "t"
                                   :mcp mcp})]
      (is (= "/Users/pez/Projects/my-app" (:workspaceRoot env)))
      (is (= mcp (:mcp env)))))
  (testing "appId and workspaceFolder are included when provided"
    (let [env (sut/build-envelope {:server-name "backseat-driver"
                                   :window-id "ws-1a2b3c"
                                   :app-id "cursor"
                                   :workspace-root "/ws/app.code-workspace"
                                   :workspace-folder "/Users/pez/Projects/my-app"
                                   :hostname "host"
                                   :pid 1
                                   :updated-at "t"})]
      (is (= "cursor" (:appId env)))
      (is (= "/ws/app.code-workspace" (:workspaceRoot env)))
      (is (= "/Users/pez/Projects/my-app" (:workspaceFolder env))))))

(deftest mcp-info-test
  (testing "nil when no assigned port"
    (is (nil? (sut/mcp-info {:server/host "127.0.0.1"} {}))))
  (testing "includes host, port, portFilePath, wrapperPath"
    (let [info (sut/mcp-info
                {:server/host "127.0.0.1"
                 :lifecycle/wrapper-install-dir "/home/x/.config/ext"
                 :cursor/script-relative-path "dist/calva-mcp-server.js"}
                {:server/assigned-port 1664
                 :server/host "127.0.0.1"
                 :server/port-file-uri #js {:fsPath "/tmp/port"}})]
      (is (= "127.0.0.1" (:host info)))
      (is (= 1664 (:port info)))
      (is (= "/tmp/port" (:portFilePath info)))
      (is (= (path/join "/home/x/.config/ext" "calva-mcp-server.js")
             (:wrapperPath info))))))

(deftest merge-custom-data-test
  (let [envelope (sut/build-envelope {:server-name "backseat-driver"
                                      :window-id "ws-1"
                                      :hostname "h"
                                      :pid 1
                                      :updated-at "t"})]
    (testing "sessions merge in"
      (is (= [{:replSessionKey "clj"}]
             (:sessions (sut/merge-custom-data envelope {:sessions [{:replSessionKey "clj"}]})))))
    (testing "core keys are protected"
      (let [merged (sut/merge-custom-data envelope {:pid 999
                                                    :name "hijack"
                                                    :serverName "other"
                                                    :appId "hijack"
                                                    :workspaceFolder "/x"
                                                    :sessions [1]
                                                    :schemaVersion 99})]
        (is (= 1 (:pid merged)))
        (is (= "backseat-driver-ws-1" (:name merged)))
        (is (= "backseat-driver" (:serverName merged)))
        (is (= 1 (:schemaVersion merged)))
        (is (nil? (:appId merged)))
        (is (nil? (:workspaceFolder merged)))
        (is (= [1] (:sessions merged)))))
    (testing "nil custom-data leaves envelope"
      (is (= envelope (sut/merge-custom-data envelope nil))))))

(deftest pid-alive-test
  (testing "current process is alive"
    (is (true? (sut/pid-alive? (sut/current-pid)))))
  (testing "unlikely pid is dead"
    (is (false? (sut/pid-alive? 99999999)))))

(deftest atomic-write-and-sweep-test
  (let [tmp-root (fs/mkdtempSync (path/join (os/tmpdir) "vscode-mcp-registry-"))
        dest (path/join tmp-root "backseat-driver-ws-abc.json")]
    (try
      (testing "atomic write produces parseable JSON and no leftover tmp"
        (sut/atomic-write! dest {:schemaVersion 1 :pid (sut/current-pid) :name "x"})
        (is (fs/existsSync dest))
        (let [doc (js->clj (js/JSON.parse (fs/readFileSync dest "utf8"))
                           :keywordize-keys true)]
          (is (= 1 (:schemaVersion doc)))
          (is (= (sut/current-pid) (:pid doc))))
        (is (empty? (filter #(re-find #"\.tmp$" %)
                            (array-seq (fs/readdirSync tmp-root))))))
      (testing "sweep keeps live-pid entry and removes dead-pid entry"
        (let [dead (path/join tmp-root "dead.json")]
          (fs/writeFileSync dead "{\"pid\":99999999}")
          (sut/sweep-dead-pid-files! tmp-root)
          (is (fs/existsSync dest))
          (is (not (fs/existsSync dead)))))
      (testing "sweep removes leftover tmp with dead pid"
        (let [stale-tmp (path/join tmp-root "x.json.99999999.1.tmp")]
          (fs/writeFileSync stale-tmp "partial")
          (sut/sweep-dead-pid-files! tmp-root)
          (is (not (fs/existsSync stale-tmp)))))
      (finally
        (fs/rmSync tmp-root #js {:recursive true :force true})))))
