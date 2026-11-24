(ns vscode-mcp.window-lookup-test
  (:require
   ["child_process" :as child-process]
   ["path" :as path]
   [cljs.test :refer [async deftest is testing]]
   [clojure.string :as string]
   [promesa.core :as p]
   [vscode-mcp.stdio.window-lookup :as sut]))

(def ext-host-env
  {"VSCODE_CRASH_REPORTER_PROCESS_TYPE" "extensionHost"
   "VSCODE_PID" "100"})

(deftest parse-process-table-test
  (is (= {43531 {:ppid 43528 :created nil}
          1 {:ppid 0 :created nil}}
         (sut/parse-process-table "  43531 43528\n    1     0\n\n")))
  (is (= {8 {:ppid 4 :created 13406303600123}}
         (sut/parse-process-table "8 4 13406303600123\r\n"))
      "Windows lines carry a creation time in milliseconds since 1601")
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

(deftest powershell-path-test
  (is (= "D:\\Win\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
         (sut/powershell-path "D:\\Win"))
      "PowerShell is found under the system root, not looked up by name")
  (is (= "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
         (sut/powershell-path nil))
      "Without SystemRoot, the system root is C:\\Windows"))

(def constrained-language-prefix
  "$ExecutionContext.SessionState.LanguageMode='ConstrainedLanguage'; ")

(defn- run-powershell
  "Output of `script`, after `prefix`, run by the process-table command's PowerShell."
  [prefix script]
  (let [[cmd args] (sut/process-table-command)]
    (child-process/execFileSync cmd
                                (clj->js (conj (pop args) (str prefix script)))
                                #js {:encoding "utf8"
                                     :windowsHide true})))

(deftest read-process-table-test
  (is (= js/process.ppid (get-in (sut/read-process-table) [js/process.pid :ppid]))
      "The read finds this process's parent")
  (when (= "win32" js/process.platform)
    (let [[cmd] (sut/process-table-command)
          probe "try { [System.Console]::WriteLine('full') } catch { 'constrained' }"
          table (sut/parse-process-table
                 (run-powershell constrained-language-prefix sut/process-table-script))
          {:keys [ppid created]} (get table js/process.pid)
          parent-created (get-in table [js/process.ppid :created])]
      (is (path/isAbsolute cmd)
          "PowerShell is started by its absolute path")
      (is (= "full" (string/trim (run-powershell "" probe)))
          "Without the prefix, the probe runs in full language mode")
      (is (= "constrained" (string/trim (run-powershell constrained-language-prefix probe)))
          "The prefix puts the rest of the command in constrained language mode")
      (is (= js/process.ppid ppid)
          "In constrained language mode, the read finds this process's parent")
      (is (and (number? created)
               (number? parent-created)
               (<= parent-created created))
          "The creation times put the parent first"))))

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
      "In a terminal, the variable names the window")
  (is (= {:caller/env-window-id "ws-b"}
         (sut/caller-search (delay (throw (js/Error. "The process table was read")))
                            {"VSCODE_MCP_WINDOW_ID" "ws-b"}))
      "In a terminal the process table isn't read")
  (is (= 38 (:caller/extension-host-pid
             (sut/caller-search (delay [[50 38] [38 100]]) ext-host-env)))
      "Under an extension host it is"))

(deftest parse-extension-host-test
  (is (= [100 38] (sut/parse-extension-host "100:38")))
  (is (every? nil? (map sut/parse-extension-host
                        [nil "" "100" "100:" ":38" "100:38:1" " 100:38" "a:b" "-1:38"
                         "99999999999999999999:38"]))
      "Anything but two pids joined by a colon is no value"))

(deftest caller-search-extension-host-env-test
  (let [unread (delay (throw (js/Error. "The process table was read")))
        ancestry (delay [[50 39] [39 100] [100 1]])]
    (is (= {:caller/vscode-pid 100 :caller/extension-host-pid 38}
           (sut/caller-search unread (assoc ext-host-env "VSCODE_MCP_EXTENSION_HOST" "100:38")))
        "The variable names the extension host, and the process table isn't read")
    (is (= 39 (:caller/extension-host-pid
               (sut/caller-search ancestry (assoc ext-host-env "VSCODE_MCP_EXTENSION_HOST" "200:38"))))
        "A value from another VS Code's extension host falls back to the ancestry")
    (is (= 39 (:caller/extension-host-pid
               (sut/caller-search ancestry (assoc ext-host-env "VSCODE_MCP_EXTENSION_HOST" "100-38"))))
        "A malformed value falls back to the ancestry")
    (is (= 39 (:caller/extension-host-pid (sut/caller-search ancestry ext-host-env)))
        "No variable falls back to the ancestry")
    (is (nil? (:caller/extension-host-pid
               (sut/caller-search [[50 1] [1 nil]]
                                  (-> ext-host-env
                                      (dissoc "VSCODE_PID")
                                      (assoc "VSCODE_MCP_EXTENSION_HOST" "100:38")))))
        "Without VSCODE_PID, the variable isn't taken")
    (is (= {:caller/env-window-id "ws-b"}
           (sut/caller-search unread {"VSCODE_MCP_EXTENSION_HOST" "100:38"
                                      "VSCODE_MCP_WINDOW_ID" "ws-b"}))
        "Outside an extension host, the variable is ignored")))

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

(deftest extension-host-env-test
  (let [env (assoc ext-host-env "VSCODE_MCP_EXTENSION_HOST" "100:7")
        shared [{:serverName "joyride" :windowId "ws-a" :pid 38
                 :mcp {:wrapperPath "/home/u/.config/joyride/joyride-mcp-server.js"}}
                {:serverName "joyride" :windowId "ws-a" :pid 7
                 :mcp {:wrapperPath "/home/u/.config/joyride/joyride-mcp-server.js"}}]]
    (is (= 7 (get-in (sut/target shared (sut/caller-search [] env) "joyride-mcp-server.js")
                     [:target/entry :pid]))
        "The variable's extension host picks its entry among windows that share a windowId")))

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

(deftest caller-window-port-test
  (async done
         (let [entries (update-in entries [2 :mcp] assoc
                                  :portFilePath "/pf/joyride-ws-b.port"
                                  :host "::")
               read-port+ (fn [port-file]
                            (p/resolved (get {"/pf/joyride-ws-b.port" 4711} port-file)))]
           (-> (p/let [found (sut/caller-window-port+ entries {:caller/env-window-id "ws-b"}
                                                      "joyride-mcp-server.js" read-port+)
                       no-entry (sut/caller-window-port+ entries {:caller/env-window-id "ws-c"}
                                                         "joyride-mcp-server.js" read-port+)
                       no-port (sut/caller-window-port+ (assoc-in entries [2 :mcp :portFilePath] "/pf/gone.port")
                                                        {:caller/env-window-id "ws-b"}
                                                        "joyride-mcp-server.js" read-port+)]
                 (is (= {:port 4711 :host "::"} found)
                     "The entry's host comes with the port from its port file")
                 (is (nil? no-entry)
                     "A window without the wrapper's server gives nothing to connect to")
                 (is (nil? no-port)
                     "A port file that can't be read gives nothing to connect to"))
               (p/catch (fn [e]
                          (is (nil? e) "The port source doesn't fail")))
               (p/then (fn [_] (done)))))))

(deftest failure-message-test
  (let [message (sut/failure-message entries {:caller/env-window-id nil} "joyride-mcp-server.js")]
    (is (re-find #"No window found for joyride" message))
    (is (re-find #"there is neither" message))
    (is (re-find #"ECA auto-registration setting" message)
        "A managed ECA entry is rewritten on every start, unless the setting is off")
    (is (re-find #"joyride  ws-b  /p/b" message)
        "The live windows of the wrapper's server are listed")
    (is (not (re-find #"ws-c" message))))
  (is (= "Found the window ws-c, but no live joyride entry in it."
         (sut/failure-message entries {:caller/env-window-id "ws-c"} "joyride-mcp-server.js"))))
