(ns vscode-mcp.window-lookup-test
  (:require
   ["child_process" :as child-process]
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [cljs.test :refer [async deftest is testing]]
   [clojure.string :as string]
   [promesa.core :as p]
   [vscode-mcp.stdio.window-lookup :as sut]))

(def ext-host-env
  {"VSCODE_CRASH_REPORTER_PROCESS_TYPE" "extensionHost"
   "VSCODE_PID" "100"})

(def here
  {:caller/working-dir "/p/b/src"
   :caller/platform "linux"})

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
         (sut/caller-search [[50 40] [40 38] [38 100] [100 1]] ext-host-env here))
      "Under an extension host, the ancestor whose parent is VSCODE_PID is the extension host")
  (is (= 38 (:caller/extension-host-pid
             (sut/caller-search [[50 38] [38 100] [100 70] [70 9] [9 200] [200 1]] ext-host-env here)))
      "A test VS Code started under another window stops at its own extension host")
  (is (= {:caller/vscode-pid 100 :caller/extension-host-pid 38}
         (sut/caller-search [[38 100]] (assoc ext-host-env "VSCODE_MCP_WINDOW_ID" "ws-b") here))
      "Under an extension host, an inherited VSCODE_MCP_WINDOW_ID is ignored")
  (is (nil? (:caller/extension-host-pid
              (sut/caller-search [[50 1] [1 nil]] (dissoc ext-host-env "VSCODE_PID") here)))
      "Without VSCODE_PID, the top of the ancestry is no extension host")
  (is (= {:caller/env-window-id "ws-b"}
         (sut/caller-search [[50 1]] {"VSCODE_MCP_WINDOW_ID" "ws-b"} here))
      "In a terminal, the variable names the window")
  (is (= {:caller/env-window-id "ws-b"}
         (sut/caller-search (delay (throw (js/Error. "The process table was read")))
                            {"VSCODE_MCP_WINDOW_ID" "ws-b"} here))
      "In a terminal the process table isn't read")
  (is (= 38 (:caller/extension-host-pid
             (sut/caller-search (delay [[50 38] [38 100]]) ext-host-env here)))
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
           (sut/caller-search unread (assoc ext-host-env "VSCODE_MCP_EXTENSION_HOST" "100:38") here))
        "The variable names the extension host, and the process table isn't read")
    (is (= 39 (:caller/extension-host-pid
               (sut/caller-search ancestry (assoc ext-host-env "VSCODE_MCP_EXTENSION_HOST" "200:38") here)))
        "A value from another VS Code's extension host falls back to the ancestry")
    (is (= 39 (:caller/extension-host-pid
               (sut/caller-search ancestry (assoc ext-host-env "VSCODE_MCP_EXTENSION_HOST" "100-38") here)))
        "A malformed value falls back to the ancestry")
    (is (= 39 (:caller/extension-host-pid (sut/caller-search ancestry ext-host-env here)))
        "No variable falls back to the ancestry")
    (is (nil? (:caller/extension-host-pid
               (sut/caller-search [[50 1] [1 nil]]
                                  (-> ext-host-env
                                      (dissoc "VSCODE_PID")
                                      (assoc "VSCODE_MCP_EXTENSION_HOST" "100:38")) here)))
        "Without VSCODE_PID, the variable isn't taken")
    (is (= {:caller/env-window-id "ws-b"}
           (sut/caller-search unread {"VSCODE_MCP_EXTENSION_HOST" "100:38"
                                      "VSCODE_MCP_WINDOW_ID" "ws-b"} here))
        "Outside an extension host, the variable is ignored")))

(deftest caller-search-working-dir-test
  (let [unread (delay (throw (js/Error. "The process table was read")))]
    (is (= here (sut/caller-search unread {} here))
        "Outside an extension host, with no VSCODE_MCP_WINDOW_ID, the working directory finds the window")
    (is (= {:caller/env-window-id "ws-b"}
           (sut/caller-search unread {"VSCODE_MCP_WINDOW_ID" "ws-b"} here))
        "VSCODE_MCP_WINDOW_ID comes before the working directory")
    (is (= {:caller/vscode-pid 100 :caller/extension-host-pid 38}
           (sut/caller-search [[38 100]] ext-host-env here))
        "Under an extension host, the working directory isn't used")
    (is (= {:caller/env-window-id nil}
           (sut/caller-search unread {} {:caller/working-dir nil :caller/platform "linux"}))
        "Without a working directory there is nothing to look for")))

(deftest folder-distance-posix-test
  (is (= [0 2 1] (map #(sut/folder-distance "linux" "/p/a" %) ["/p/a" "/p/a/b/c" "/p/a/..b"]))
      "The folder itself and its subfolders hold the directory")
  (is (every? nil? (map #(sut/folder-distance "linux" "/p/a" %) ["/p/ab" "/p" "/q/a" "/p/A"]))
      "A sibling whose name starts the same, a parent, another tree, and on Linux another letter case don't")
  (is (= 2 (sut/folder-distance "linux" "/" "/p/a"))))

(deftest folder-distance-windows-test
  (is (= 1 (sut/folder-distance "win32" "c:\\Users\\Pez\\proj" "C:\\users\\pez\\PROJ\\src"))
      "On Windows, letter case, drive letter included, doesn't count")
  (is (= 0 (sut/folder-distance "win32" "C:/Users/pez/proj/" "C:\\Users\\pez\\proj"))
      "On Windows, both separators work, and a trailing one doesn't count")
  (is (every? nil? [(sut/folder-distance "win32" "C:\\foo" "C:\\foobar")
                    (sut/folder-distance "win32" "C:\\foo" "D:\\foo")])
      "On Windows, a sibling whose name starts the same and another drive don't hold the directory")
  (is (= 1 (sut/folder-distance "win32" "\\\\server\\share\\p" "\\\\SERVER\\share\\p\\q"))
      "UNC paths compare too"))

(deftest real-path-test
  (let [tmp (fs/mkdtempSync (path/join (os/tmpdir) "window-lookup-"))
        folder (path/join tmp "project")
        link (path/join tmp "link")]
    (try
      (fs/mkdirSync folder)
      (fs/symlinkSync folder link "junction")
      (is (= (sut/real-path folder) (sut/real-path link))
          "A symlinked folder is its target")
      (is (= (sut/real-path folder) (sut/real-path (path/join link ".." "project")))
          "A path through a symlink is the path it leads to")
      (let [other-case (string/upper-case folder)]
        (when (fs/existsSync other-case)
          (is (= (sut/real-path folder) (sut/real-path other-case))
              "On a file system that ignores letter case, another case is the same folder")))
      (is (= (path/resolve "no-such-folder") (sut/real-path "no-such-folder"))
          "A path that doesn't exist is made absolute")
      (finally
        (fs/rmSync tmp #js {:recursive true :force true})))))

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
    (is (= 7 (get-in (sut/target shared (sut/caller-search [] env here) "joyride-mcp-server.js")
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
  (is (false? (sut/settled-without-window? [] {:caller/extension-host-pid 42} "joyride-mcp-server.js"))
      "Under an extension host, the window's entry may still come")
  (is (true? (sut/settled-without-window? [] {:caller/vscode-pid 100 :caller/extension-host-pid nil}
                                             "joyride-mcp-server.js")))
  (is (true? (sut/settled-without-window? entries {:caller/env-window-id "ws-gone"} "joyride-mcp-server.js")))
  (is (true? (sut/settled-without-window? entries {:caller/env-window-id nil} "joyride-mcp-server.js")))
  (is (false? (sut/settled-without-window? entries {:caller/env-window-id "ws-b"} "joyride-mcp-server.js"))))

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


(defn- in-folder
  [entry folder]
  (assoc entry :workspaceFolder folder :entry/real-folder folder))

(def folder-entries
  [(in-folder {:serverName "joyride" :windowId "ws-p" :pid 10
               :mcp {:wrapperPath "/h/joyride-mcp-server.js"}}
              "/p")
   (in-folder {:serverName "joyride" :windowId "ws-b" :pid 11
               :workspaceRoot "/w/b.code-workspace"
               :mcp {:wrapperPath "/h/joyride-mcp-server.js"}}
              "/p/b")
   (in-folder {:serverName "calva-backseat-driver" :windowId "ws-s" :pid 12
               :mcp {:wrapperPath "/h/calva-mcp-server.js"}}
              "/p/b/src")
   {:serverName "joyride" :windowId "ws-none" :pid 13
    :mcp {:wrapperPath "/h/joyride-mcp-server.js"}}])

(defn- working-dir-pid
  "pid of the entry `target` finds for `working-dir` among `entries`."
  ([entries working-dir]
   (working-dir-pid entries working-dir "linux"))
  ([entries working-dir platform]
   (get-in (sut/target entries {:caller/working-dir working-dir :caller/platform platform}
                       "joyride-mcp-server.js")
           [:target/entry :pid])))

(deftest working-dir-target-test
  (is (= 11 (working-dir-pid folder-entries "/p/b"))
      "The window whose folder is the working directory")
  (is (= 11 (working-dir-pid folder-entries "/p/b/src/x"))
      "A subfolder of the window's folder, where another server's deeper entry doesn't count")
  (is (= 10 (working-dir-pid folder-entries "/p/c"))
      "The deepest folder holding the working directory wins")
  (is (= {:target/window-id "ws-b" :target/entry (second folder-entries)}
         (sut/target folder-entries here "joyride-mcp-server.js")))
  (is (nil? (working-dir-pid folder-entries "/q"))
      "No folder holds the working directory"))

(deftest working-dir-target-choice-test
  (is (nil? (working-dir-pid (conj folder-entries (assoc (second folder-entries) :windowId "ws-b2" :pid 14))
                             "/p/b/src"))
      "Two entries with the same deepest folder are no choice")
  (is (= 11 (working-dir-pid (into [(assoc (second folder-entries) :pid 15 :entry/real-folder "/p")]
                                   folder-entries)
                             "/p/b/src"))
      "Of two entries that share a windowId, the one that matched")
  (is (= 16 (working-dir-pid [(in-folder {:serverName "joyride" :windowId "ws-w" :pid 16
                                          :mcp {:wrapperPath "C:\\h\\joyride-mcp-server.js"}}
                                         "c:\\Users\\Pez\\proj")]
                             "C:\\Users\\pez\\proj\\src"
                             "win32"))
      "Windows paths match across drive-letter case"))

(deftest working-dir-settling-test
  (is (false? (sut/settled-without-window? folder-entries here "joyride-mcp-server.js"))
      "A matching entry is a window")
  (is (true? (sut/settled-without-window? folder-entries
                                           {:caller/working-dir "/q" :caller/platform "linux"}
                                           "joyride-mcp-server.js"))
      "With no match, the wrapper waits only the short budget")
  (async done
         (let [entries (assoc-in folder-entries [1 :mcp :portFilePath] "/pf/joyride-ws-b.port")
               read-port+ (fn [port-file]
                            (p/resolved (get {"/pf/joyride-ws-b.port" 4711} port-file)))]
           (-> (p/let [before (sut/caller-window-port+ [] here "joyride-mcp-server.js" read-port+)
                       after (sut/caller-window-port+ entries here "joyride-mcp-server.js" read-port+)]
                 (is (nil? before)
                     "No entry yet, nothing to connect to")
                 (is (= 4711 (:port after))
                     "An entry that appears while the wrapper tries again connects"))
               (p/catch (fn [e]
                          (is (nil? e) "The port source doesn't fail")))
               (p/then (fn [_] (done)))))))

(deftest working-dir-failure-message-test
  (let [message (sut/failure-message folder-entries
                                     {:caller/working-dir "/q" :caller/platform "linux"}
                                     "joyride-mcp-server.js")]
    (is (re-find #"No window found for joyride.*/q.*first folder counts" message)
        "It names the working directory, and that only a window's first folder counts")
    (is (re-find #"joyride  ws-b  /p/b" message)
        "The live windows are listed with their first folder")
    (is (re-find #"joyride  ws-none  no folder" message))
    (is (not (re-find #"ws-s" message)))))
