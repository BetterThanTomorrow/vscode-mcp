(ns vscode-mcp.stdio.window-lookup
  "Finds the window the stdio wrapper runs in, from its process ancestry, its
   environment and the window registry. The same rule as `bb list` and `bb mcp`
   in the registry home's `scripts/list_registry.clj`."
  (:require
   ["child_process" :as child-process]
   ["fs" :as fs]
   ["path" :as path]
   [clojure.string :as string]
   [vscode-mcp.registry :as registry]))

(def ttl-ms
  60000)

;; Process ancestry

(defn parse-process-table
  "`{pid {:ppid … :created …}}` from lines of `pid ppid [created]`."
  [out]
  (->> (string/split-lines (or out ""))
       (keep (fn [line]
               (let [[pid ppid created] (map parse-long (string/split (string/trim line) #"\s+"))]
                 (when (and pid ppid)
                   [pid {:ppid ppid :created created}]))))
       (into {})))

(defn- parent-of
  "Parent pid of `pid` in `table`. A parent created after its child is a reused pid, not the parent."
  [table pid]
  (let [{:keys [ppid created]} (get table pid)
        parent-created (get-in table [ppid :created])]
    (when (and ppid
               (not (and created parent-created (> parent-created created))))
      ppid)))

(defn ancestry
  "[pid parent-pid] pairs from `pid` up, nearest first."
  [table pid]
  (loop [pid pid
         acc []]
    (let [ppid (parent-of table pid)]
      (if (and ppid (pos? ppid) (< (count acc) 64))
        (recur ppid (conj acc [pid ppid]))
        (conj acc [pid ppid])))))

(defn parse-proc-stat
  "Parent pid from the text of a Linux `/proc/<pid>/stat` file. The command name, in
   parentheses, can hold spaces and parentheses, so the fields are read after the last `)`."
  [text]
  (when-let [i (some-> text (string/last-index-of ")"))]
    (let [[_state ppid] (string/split (string/trim (subs text (inc i))) #"\s+")]
      (parse-long ppid))))

(defn proc-chain
  "The process table for `pid` and its ancestors, read one `/proc/<pid>/stat` text at a
   time with `read-stat`. Linux gives a process whose parent exits a new parent, so a
   parent pid there is never a reused one."
  [read-stat pid]
  (loop [pid pid
         table {}]
    (let [ppid (parse-proc-stat (read-stat pid))
          table (cond-> table ppid (assoc pid {:ppid ppid}))]
      (if (and ppid
               (pos? ppid)
               (not (contains? table ppid))
               (< (count table) 64))
        (recur ppid table)
        table))))

(defn- windows?
  []
  (= "win32" js/process.platform))

(defn- process-table-command
  []
  (if (windows?)
    ["powershell.exe" ["-NoProfile" "-NonInteractive" "-Command"
                       "Get-CimInstance Win32_Process | ForEach-Object { '{0} {1} {2}' -f $_.ProcessId, $_.ParentProcessId, $(if ($_.CreationDate) { [DateTimeOffset]::new($_.CreationDate).ToUnixTimeMilliseconds() } else { '' }) }"]]
    ["ps" ["-A" "-o" "pid=" "-o" "ppid="]]))

(defn read-process-table
  "The process table, from Linux's /proc when it is there, else from `ps` or PowerShell.
   An empty map, with the reason on stderr, when it can't be read."
  []
  (try
    (if (fs/existsSync "/proc/self/stat")
      (proc-chain #(try (fs/readFileSync (str "/proc/" % "/stat") "utf8")
                        (catch :default _ nil))
                  js/process.pid)
      (let [[cmd args] (process-table-command)]
        (parse-process-table (child-process/execFileSync cmd (clj->js args)
                                                         #js {:encoding "utf8"
                                                              :windowsHide true}))))
    (catch :default e
      (.write js/process.stderr (str "[Wrapper] Couldn't read the process table: " (.-message e) "\n"))
      {})))

;; The caller's window

(defn caller-search
  "What identifies the caller's window. Under an extension host, which VS Code marks
   in the environment it passes down, the extension host is the nearest ancestor whose
   parent is VS Code's main process, `VSCODE_PID`. Otherwise it is the window
   `VSCODE_MCP_WINDOW_ID` names. `ancestry` may be a delay, forced only under an
   extension host."
  [ancestry env]
  (if (= "extensionHost" (get env "VSCODE_CRASH_REPORTER_PROCESS_TYPE"))
    (let [vscode-pid (some-> (get env "VSCODE_PID") parse-long)]
      {:caller/vscode-pid vscode-pid
       :caller/extension-host-pid (when vscode-pid
                                    (some (fn [[pid ppid]]
                                            (when (= vscode-pid ppid) pid))
                                          (force ancestry)))})
    {:caller/env-window-id (get env registry/window-id-env)}))

(defn caller-window-id
  "windowId of the caller's window among live `entries`, or nil."
  [entries {:caller/keys [extension-host-pid env-window-id]}]
  (cond
    extension-host-pid (some #(when (= extension-host-pid (:pid %)) (:windowId %)) entries)
    env-window-id (when (some #(= env-window-id (:windowId %)) entries)
                    env-window-id)))

(defn caller-search-text
  "What `search` looked for, for a message."
  [{:caller/keys [vscode-pid extension-host-pid env-window-id] :as search}]
  (cond
    extension-host-pid (str "the window whose extension host is pid " extension-host-pid)
    vscode-pid (str "an extension host of VS Code pid " vscode-pid
                    " among this process's ancestors, and there is none")
    (contains? search :caller/vscode-pid) (str "an extension host, but VS Code's main process isn't in"
                                               " the environment, VSCODE_PID")
    env-window-id (str "the window " registry/window-id-env " names, " env-window-id)
    :else (str "a VS Code extension host among this process's ancestors, or "
               registry/window-id-env ", and there is neither")))

(defn settled-without-window?
  "True when waiting can't bring a window: not under an extension host, or under one
   that isn't among the ancestors, and no live entry for the variable's window."
  [entries {:caller/keys [extension-host-pid] :as search}]
  (and (nil? extension-host-pid)
       (nil? (caller-window-id entries search))))

;; The wrapper's own server

(defn- file-name
  [p]
  (when p
    (path/basename (string/replace (str p) "\\" "/"))))

(defn own-server?
  "True when `entry`'s installed wrapper has the file name of the running `script-name`."
  [script-name entry]
  (= script-name (file-name (get-in entry [:mcp :wrapperPath]))))

(defn target
  "The caller's window among live `entries`, and the entry of the wrapper's own server in it.
   Under an extension host, the entry must have its pid too: another window on the same
   workspace shares the windowId."
  [entries {:caller/keys [extension-host-pid] :as search} script-name]
  (when-let [window-id (caller-window-id entries search)]
    {:target/window-id window-id
     :target/entry (some #(when (and (= window-id (:windowId %))
                                     (own-server? script-name %)
                                     (or (nil? extension-host-pid)
                                         (= extension-host-pid (:pid %))))
                            %)
                         entries)}))

(defn- window-line
  [{:keys [serverName windowId workspaceRoot]}]
  (string/join "  " [serverName windowId (or workspaceRoot "no folder")]))

(defn failure-message
  "Why the wrapper found nothing to connect to, with the live windows of its server."
  [entries search script-name]
  (let [own (filterv #(own-server? script-name %) entries)
        server (or (:serverName (first own)) script-name)]
    (if-let [window-id (caller-window-id entries search)]
      (str "Found the window " window-id ", but no live " server " entry in it.")
      (str "No window found for " server ". Looked for " (caller-search-text search)
           ". Give the wrapper the port file of the window to connect to. Live windows:\n"
           (string/join "\n" (map window-line own))))))

;; Registry

(defn live?
  [entry now-ms]
  (boolean
   (and (:pid entry)
        (registry/pid-alive? (:pid entry))
        (when-let [updated (some-> (:updatedAt entry) js/Date.parse)]
          (< (- now-ms updated) ttl-ms)))))

(defn read-live-entries
  "Live entries in the registry's windows directory."
  []
  (let [dir (registry/default-dir)
        now-ms (js/Date.now)]
    (if-not (fs/existsSync dir)
      []
      (->> (fs/readdirSync dir)
           (filter #(string/ends-with? % ".json"))
           (keep #(try
                    (js->clj (js/JSON.parse (fs/readFileSync (path/join dir %) "utf8"))
                             :keywordize-keys true)
                    (catch :default _ nil)))
           (filterv #(live? % now-ms))))))

(defn current-caller-search
  "`caller-search` for this process."
  []
  (caller-search (delay (ancestry (read-process-table) js/process.pid))
                 (into {} (map vec) (js/Object.entries (.-env js/process)))))
