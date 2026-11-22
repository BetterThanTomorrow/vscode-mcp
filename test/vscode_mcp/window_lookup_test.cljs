(ns vscode-mcp.window-lookup-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.stdio.window-lookup :as sut]))

(def ext-host-env
  {"VSCODE_CRASH_REPORTER_PROCESS_TYPE" "extensionHost"
   "VSCODE_PID" "100"})

(deftest parse-process-table-test
  (is (= {43531 {:ppid 43528 :created nil}
          1 {:ppid 0 :created nil}}
         (sut/parse-process-table "  43531 43528\n    1     0\n\n")))
  (is (= {8 {:ppid 4 :created 1763830000123}}
         (sut/parse-process-table "8 4 1763830000123\r\n"))
      "Windows lines carry a creation time in Unix milliseconds")
  (is (= {4 {:ppid 0 :created nil}}
         (sut/parse-process-table "4 0 \r\n"))
      "A process without a creation time has none"))

(deftest parse-proc-stat-test
  (is (= 38 (sut/parse-proc-stat "50 (node) S 38 50 38 0 -1 4194560 1234\n")))
  (is (= 38 (sut/parse-proc-stat "50 (code ) (helper) S 38 50 38 0 -1\n"))
      "The command name can hold spaces and parentheses")
  (is (nil? (sut/parse-proc-stat nil))
      "No text, no parent"))

(deftest proc-chain-test
  (let [stats {50 "50 (bb) S 40 1 1"
               40 "40 (sh) S 38 1 1"
               38 "38 (code) S 1 1 1"
               1 "1 (init) S 0 1 1"}]
    (is (= {50 {:ppid 40} 40 {:ppid 38} 38 {:ppid 1} 1 {:ppid 0}}
           (sut/proc-chain stats 50)))
    (is (= [[50 40] [40 38] [38 1] [1 0]]
           (sut/ancestry (sut/proc-chain stats 50) 50))
        "The chain is a process table for the walk"))
  (is (= {50 {:ppid 40}}
         (sut/proc-chain {50 "50 (bb) S 40 1 1"} 50))
      "A stat that can't be read ends the chain"))

(deftest ancestry-test
  (is (= [[50 40] [40 38] [38 100] [100 1] [1 0]]
         (sut/ancestry {50 {:ppid 40} 40 {:ppid 38} 38 {:ppid 100} 100 {:ppid 1} 1 {:ppid 0}} 50)))
  (is (= [[50 40] [40 nil]]
         (sut/ancestry {50 {:ppid 40 :created 20} 40 {:ppid 30 :created 10} 30 {:ppid 1 :created 15}} 50))
      "A parent created after its child is a reused pid, and the walk stops"))

(deftest caller-search-test
  (is (= {:caller/vscode-pid 100 :caller/extension-host-pid 38}
         (sut/caller-search [[50 40] [40 38] [38 100] [100 1]] ext-host-env))
      "Under an extension host, the ancestor whose parent is VSCODE_PID is the extension host")
  (is (= 38 (:caller/extension-host-pid
             (sut/caller-search [[50 38] [38 100] [100 70] [70 9] [9 200] [200 1]] ext-host-env)))
      "A test VS Code started under another window stops at its own extension host")
  (is (= {:caller/vscode-pid 100 :caller/extension-host-pid 38}
         (sut/caller-search [[38 100]] (assoc ext-host-env "VSCODE_MCP_WINDOW_ID" "ws-b")))
      "Under an extension host, an inherited VSCODE_MCP_WINDOW_ID is ignored")
  (is (nil? (:caller/extension-host-pid
              (sut/caller-search [[50 1] [1 nil]] (dissoc ext-host-env "VSCODE_PID"))))
      "Without VSCODE_PID, the top of the ancestry is no extension host")
  (is (= {:caller/env-window-id "ws-b"}
         (sut/caller-search [[50 1]] {"VSCODE_MCP_WINDOW_ID" "ws-b"}))
      "In a terminal, the variable names the window"))

(def entries
  [{:serverName "joyride" :windowId "ws-a" :pid 38
    :mcp {:wrapperPath "/home/u/.config/joyride/joyride-mcp-server.js"}}
   {:serverName "calva-backseat-driver" :windowId "ws-a" :pid 38
    :mcp {:wrapperPath "/home/u/.config/calva/backseat-driver/calva-mcp-server.js"}}
   {:serverName "joyride" :windowId "ws-b" :pid 7
    :workspaceRoot "/p/b"
    :mcp {:wrapperPath "C:\\Users\\u\\.config\\joyride\\joyride-mcp-server.js"}}
   {:serverName "calva-backseat-driver" :windowId "ws-c" :pid 9
    :mcp {:wrapperPath "/home/u/.config/calva/backseat-driver/calva-mcp-server.js"}}])

(deftest target-test
  (testing "the wrapper's own server in the caller's window"
    (is (= ["ws-a" "joyride"]
           ((juxt :target/window-id (comp :serverName :target/entry))
            (sut/target entries {:caller/extension-host-pid 38} "joyride-mcp-server.js"))))
    (is (= "calva-backseat-driver"
           (:serverName (:target/entry (sut/target entries {:caller/extension-host-pid 38}
                                                   "calva-mcp-server.js"))))))
  (is (= "ws-b"
         (:windowId (:target/entry (sut/target entries {:caller/env-window-id "ws-b"}
                                               "joyride-mcp-server.js"))))
      "A Windows wrapperPath matches by file name")
  (is (= {:target/window-id "ws-c" :target/entry nil}
         (sut/target entries {:caller/env-window-id "ws-c"} "joyride-mcp-server.js"))
      "A window without the wrapper's server")
  (is (nil? (sut/target entries {:caller/extension-host-pid 42} "joyride-mcp-server.js"))
      "An extension host with no entry yet is no window yet")
  (is (nil? (:target/entry (sut/target (assoc-in entries [0 :pid] 99)
                                       {:caller/extension-host-pid 38}
                                       "joyride-mcp-server.js")))
      "Another window on the same workspace wrote the server's entry"))

(deftest settled-without-window-test
  (is (false? (sut/settled-without-window? [] {:caller/extension-host-pid 42}))
      "Under an extension host, the window's entry may still come")
  (is (true? (sut/settled-without-window? [] {:caller/vscode-pid 100 :caller/extension-host-pid nil})))
  (is (true? (sut/settled-without-window? entries {:caller/env-window-id "ws-gone"})))
  (is (true? (sut/settled-without-window? entries {:caller/env-window-id nil})))
  (is (false? (sut/settled-without-window? entries {:caller/env-window-id "ws-b"}))))

(deftest failure-message-test
  (let [message (sut/failure-message entries {:caller/env-window-id nil} "joyride-mcp-server.js")]
    (is (re-find #"No window found for joyride" message))
    (is (re-find #"there is neither" message))
    (is (re-find #"joyride  ws-b  /p/b" message)
        "The live windows of the wrapper's server are listed")
    (is (not (re-find #"ws-c" message))))
  (is (= "Found the window ws-c, but no live joyride entry in it."
         (sut/failure-message entries {:caller/env-window-id "ws-c"} "joyride-mcp-server.js"))))
