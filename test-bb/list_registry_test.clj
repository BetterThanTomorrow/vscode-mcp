(ns list-registry-test
  (:require
   [babashka.fs :as fs]
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

(deftest format-mcp-line-test
  (let [home (str (fs/home))
        mcp {:host "127.0.0.1"
             :wrapperPath (str home "/.config/joyride/joyride-mcp-server.js")
             :portFilePath (str home "/.config/vscode-mcp/port-files/joyride-ws-a.port")}]
    (is (= "  mcp:  node ~/.config/joyride/joyride-mcp-server.js ~/.config/vscode-mcp/port-files/joyride-ws-a.port 127.0.0.1"
           (listing/format-mcp-line mcp))
        "A window named by its port file and host")
    (is (= "  mcp:  node ~/.config/joyride/joyride-mcp-server.js"
           (listing/format-mcp-line (:mcp (listing/caller-window-snapshot {:mcp mcp}))))
        "The caller's window names no port file")
    (is (= "  mcp:  (not assigned)" (listing/format-mcp-line nil)))))

(deftest caller-window-snapshot-test
  (is (= {:serverName "joyride" :mcp {:wrapperPath "/w.js"}}
         (listing/caller-window-snapshot {:serverName "joyride"
                                          :mcp {:host "127.0.0.1" :port 1
                                                :wrapperPath "/w.js" :portFilePath "/p"}}))
      "Only the wrapper is left in mcp")
  (is (= {:serverName "joyride"} (listing/caller-window-snapshot {:serverName "joyride"}))
      "A window without mcp gets none"))

(defn- pid-of
  [^java.lang.ProcessHandle ph]
  (.pid ph))

(def own-pid
  (pid-of (java.lang.ProcessHandle/current)))

(def parent-pid
  (pid-of (.get (.parent (java.lang.ProcessHandle/current)))))

(defn- live-entry
  [server-name window-id pid]
  {:serverName server-name
   :windowId window-id
   :pid pid
   :updatedAt (str (java.time.Instant/now))})

(def entries
  [(live-entry "joyride" "ws-a" own-pid)
   (live-entry "calva-backseat-driver" "ws-a" own-pid)
   (live-entry "joyride" "ws-b" parent-pid)
   (live-entry "joyride" "ws-c" parent-pid)])

(def ext-host-env
  {"VSCODE_CRASH_REPORTER_PROCESS_TYPE" "extensionHost"
   "VSCODE_PID" "100"})

(deftest caller-search-test
  (is (= {:caller/vscode-pid 100 :caller/extension-host-pid 38}
         (listing/caller-search [[50 40] [40 38] [38 100] [100 1]] ext-host-env))
      "Under an extension host, the ancestor whose parent is VSCODE_PID is the extension host")
  (is (= 38 (:caller/extension-host-pid
             (listing/caller-search [[50 38] [38 100] [100 70] [70 9] [9 200] [200 1]]
                                    ext-host-env)))
      "A test VS Code started under another window stops at its own extension host")
  (is (= {:caller/vscode-pid 100 :caller/extension-host-pid 38}
         (listing/caller-search [[38 100]] (assoc ext-host-env "VSCODE_MCP_WINDOW_ID" "ws-b")))
      "Under an extension host, an inherited VSCODE_MCP_WINDOW_ID is ignored")
  (is (= {:caller/vscode-pid 100 :caller/extension-host-pid nil}
         (listing/caller-search [[50 1]] ext-host-env))
      "Reparented away from the extension host, there is no extension host")
  (is (nil? (:caller/extension-host-pid
              (listing/caller-search [[50 1] [1 nil]] (dissoc ext-host-env "VSCODE_PID"))))
      "Without VSCODE_PID, the top of the ancestry is no extension host")
  (is (= {:caller/env-window-id "ws-b"}
         (listing/caller-search [[50 40] [40 1]] {"VSCODE_MCP_WINDOW_ID" "ws-b"}))
      "In a terminal, the variable names the window")
  (is (= {:caller/env-window-id nil}
         (listing/caller-search [[50 1]] {}))))

(deftest caller-window-id-test
  (is (= "ws-a" (listing/caller-window-id entries {:caller/extension-host-pid own-pid})))
  (is (nil? (listing/caller-window-id entries {:caller/extension-host-pid 42}))
      "An extension host with no entry yet is no window yet")
  (is (= "ws-b" (listing/caller-window-id entries {:caller/env-window-id "ws-b"})))
  (is (nil? (listing/caller-window-id entries {:caller/env-window-id "ws-gone"}))
      "A variable naming no entry finds nothing")
  (is (nil? (listing/caller-window-id entries {:caller/env-window-id nil}))))

(deftest caller-search-text-test
  (is (re-find #"extension host is pid 38"
               (listing/caller-search-text {:caller/vscode-pid 100 :caller/extension-host-pid 38})))
  (is (re-find #"extension host of VS Code pid 100 .* there is none"
               (listing/caller-search-text {:caller/vscode-pid 100 :caller/extension-host-pid nil})))
  (is (re-find #"VSCODE_MCP_WINDOW_ID names, ws-b"
               (listing/caller-search-text {:caller/env-window-id "ws-b"})))
  (is (re-find #"there is neither"
               (listing/caller-search-text {:caller/env-window-id nil}))))

(deftest caller-listing-test
  (let [stale (assoc (live-entry "joyride" "ws-d" parent-pid)
                     :updatedAt (str (.minusSeconds (java.time.Instant/now) 120)))
        in-window {:caller/extension-host-pid own-pid}]
    (testing "inside a window"
      (let [{:keys [entries caller-window-id other-window-count]}
            (listing/caller-listing {:entries (conj entries stale)
                                     :search in-window})]
        (is (= #{"ws-a"} (set (map :windowId entries)))
            "Only the caller's window is listed")
        (is (= 2 (count entries))
            "Every server in the caller's window is listed")
        (is (= 2 other-window-count)
            "Other live windows are counted, stale ones are not")
        (is (= "ws-a" caller-window-id)
            "The listing says it is the caller's window")))
    (testing "asked for all windows"
      (let [{:keys [entries caller-window-id other-window-count]}
            (listing/caller-listing {:entries entries
                                     :search in-window
                                     :all? true})]
        (is (= 4 (count entries)))
        (is (nil? caller-window-id))
        (is (zero? other-window-count))))
    (testing "outside every window"
      (let [{:keys [entries other-window-count]}
            (listing/caller-listing {:entries entries
                                     :search {:caller/env-window-id nil}})]
        (is (= 4 (count entries)))
        (is (zero? other-window-count))))))

(deftest other-windows-line-test
  (is (nil? (listing/other-windows-line 0)))
  (is (= "1 other live window. `bb list --all` lists them."
         (listing/other-windows-line 1)))
  (is (= "3 other live windows. `bb list --all` lists them."
         (listing/other-windows-line 3))))
