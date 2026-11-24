(ns vscode-mcp.stdio.window-lookup
  "Finds the window the stdio wrapper runs in, from its environment, its process
   ancestry and the window registry, by the same rule as `bb list` and `bb mcp` in the
   registry home's `scripts/list_registry.clj`. Only the wrapper takes the last step:
   outside every window, the window whose first folder holds its working directory."
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

(def process-table-script
  "PowerShell that prints `pid ppid created` for each process, the creation time in
   milliseconds since 1601. It runs in constrained language mode."
  (str "$ProgressPreference='SilentlyContinue'; "
       "Get-CimInstance Win32_Process -Property ProcessId,ParentProcessId,CreationDate"
       " | ForEach-Object { '{0} {1} {2:F0}' -f $_.ProcessId, $_.ParentProcessId,"
       " $(if ($_.CreationDate) { $_.CreationDate.ToFileTimeUtc() / 10000 } else { '' }) }"))

(defn powershell-path
  "Windows PowerShell's absolute path under `system-root`, or under `C:\\Windows` when it is nil."
  [system-root]
  (path/win32.join (or system-root "C:\\Windows") "System32" "WindowsPowerShell" "v1.0" "powershell.exe"))

(defn process-table-command
  "`[command args]` that print the process table: PowerShell on Windows, else `ps`."
  []
  (if (windows?)
    [(powershell-path (unchecked-get js/process.env "SystemRoot"))
     ["-NoProfile" "-NonInteractive" "-Command" process-table-script]]
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

(defn parse-extension-host
  "`[vscode-pid extension-host-pid]` from a `VSCODE_MCP_EXTENSION_HOST` value, or nil
   when it isn't two pids joined by a colon."
  [value]
  (when-let [[_ & pids] (some->> value (re-matches #"(\d+):(\d+)"))]
    (let [pids (mapv parse-long pids)]
      (when (every? some? pids)
        pids))))

(defn- env-extension-host-pid
  "The extension host `VSCODE_MCP_EXTENSION_HOST` names in `env`, when its VS Code is `vscode-pid`."
  [env vscode-pid]
  (let [[env-vscode-pid extension-host-pid] (parse-extension-host (get env registry/extension-host-env))]
    (when (= vscode-pid env-vscode-pid)
      extension-host-pid)))

(defn- ancestry-extension-host-pid
  "The nearest pid in `ancestry` whose parent is `vscode-pid`."
  [ancestry vscode-pid]
  (some (fn [[pid ppid]]
          (when (= vscode-pid ppid) pid))
        ancestry))

(defn- extension-host-search
  [ancestry env]
  (let [vscode-pid (some-> (get env "VSCODE_PID") parse-long)]
    {:caller/vscode-pid vscode-pid
     :caller/extension-host-pid (when vscode-pid
                                  (or (env-extension-host-pid env vscode-pid)
                                      (ancestry-extension-host-pid (force ancestry) vscode-pid)))}))

(defn caller-search
  "What identifies the caller's window. Under an extension host, which VS Code marks
   in the environment it passes down, the extension host is the one
   `VSCODE_MCP_EXTENSION_HOST` names for VS Code's main process, `VSCODE_PID`, or else
   the nearest ancestor whose parent is that process. Otherwise it is the window
   `VSCODE_MCP_WINDOW_ID` names, or else the window whose folder holds `working-dir`, a
   real path compared by `platform`'s rules. `ancestry` may be a delay, forced only when
   the environment names no extension host."
  [ancestry env {:caller/keys [working-dir platform]}]
  (cond
    (= "extensionHost" (get env "VSCODE_CRASH_REPORTER_PROCESS_TYPE"))
    (extension-host-search ancestry env)

    (get env registry/window-id-env)
    {:caller/env-window-id (get env registry/window-id-env)}

    working-dir
    {:caller/working-dir working-dir
     :caller/platform platform}

    :else
    {:caller/env-window-id nil}))

(defn caller-window-id
  "windowId of the caller's window among live `entries`, or nil."
  [entries {:caller/keys [extension-host-pid env-window-id]}]
  (cond
    extension-host-pid (some #(when (= extension-host-pid (:pid %)) (:windowId %)) entries)
    env-window-id (when (some #(= env-window-id (:windowId %)) entries)
                    env-window-id)))

(defn caller-search-text
  "What `search` looked for, for a message."
  [{:caller/keys [vscode-pid extension-host-pid env-window-id working-dir] :as search}]
  (cond
    extension-host-pid (str "the window whose extension host is pid " extension-host-pid)
    vscode-pid (str "an extension host of VS Code pid " vscode-pid
                    " in " registry/extension-host-env " or among this process's ancestors, and there is none")
    (contains? search :caller/vscode-pid) (str "an extension host, but VS Code's main process isn't in"
                                               " the environment, VSCODE_PID")
    env-window-id (str "the window " registry/window-id-env " names, " env-window-id)
    working-dir (str "the one window whose folder is the working directory, " working-dir
                     ", or holds it. Only a window's first folder counts")
    :else (str "a VS Code extension host among this process's ancestors, or "
               registry/window-id-env ", and there is neither")))

;; The wrapper's own server

(defn- file-name
  [p]
  (when p
    (path/basename (string/replace (str p) "\\" "/"))))

(defn own-server?
  "True when `entry`'s installed wrapper has the file name of the running `script-name`."
  [script-name entry]
  (= script-name (file-name (get-in entry [:mcp :wrapperPath]))))

(defn- resolved-path
  "`p` with symlinks resolved and in the letter case on disk, or nil when it doesn't exist."
  [p]
  (try
    (fs/realpathSync.native p)
    (catch :default _ nil)))

(defn real-path
  "`p` with symlinks resolved and in the letter case on disk, or `p` made absolute when it
   doesn't exist."
  [p]
  (or (resolved-path p)
      (path/resolve p)))

(defn with-real-folders
  "`entries`, the `script-name` server's with their `workspaceFolder` as `real-path` gives
   it in `:entry/real-folder`, when `search` looks by working directory."
  [entries {:caller/keys [working-dir]} script-name real-path]
  (if working-dir
    (mapv (fn [{:keys [workspaceFolder] :as entry}]
            (cond-> entry
              (and workspaceFolder (own-server? script-name entry))
              (assoc :entry/real-folder (real-path workspaceFolder))))
          entries)
    entries))

(defn folder-distance
  "How many folders `dir` lies below `folder`: 0 when it is `folder`, nil when it is outside
   it. The paths follow `platform`'s rules, with letter case ignored on Windows."
  [platform folder dir]
  (let [win32? (= "win32" platform)
        ^js path-module (if win32? path/win32 path/posix)
        comparable (fn [p] (cond-> p win32? string/lower-case))
        rel (.relative path-module (comparable folder) (comparable dir))
        sep (.-sep path-module)]
    (when-not (or (.isAbsolute path-module rel)
                  (= ".." rel)
                  (string/starts-with? rel (str ".." sep)))
      (count (remove string/blank? (string/split rel sep))))))

(defn- working-dir-entry
  "The entry of the wrapper's own server whose `:entry/real-folder` is the deepest folder
   holding `working-dir`, or nil when none holds it or two hold it equally deep."
  [entries {:caller/keys [working-dir platform]} script-name]
  (let [by-distance (->> entries
                         (filter #(own-server? script-name %))
                         (keep (fn [{:entry/keys [real-folder] :as entry}]
                                 (when-let [distance (when real-folder
                                                       (folder-distance platform real-folder working-dir))]
                                   [distance entry])))
                         (group-by first))
        nearest (when (seq by-distance)
                  (apply min (keys by-distance)))
        [[_ entry] & more] (get by-distance nearest)]
    (when-not more
      entry)))

(defn- window-target
  [entries {:caller/keys [extension-host-pid] :as search} script-name]
  (when-let [window-id (caller-window-id entries search)]
    {:target/window-id window-id
     :target/entry (some #(when (and (= window-id (:windowId %))
                                     (own-server? script-name %)
                                     (or (nil? extension-host-pid)
                                         (= extension-host-pid (:pid %))))
                            %)
                         entries)}))

(defn target
  "The caller's window among live `entries`, and the entry of the wrapper's own server in it.
   Under an extension host, the entry must have its pid too: another window on the same
   workspace shares the windowId. From the working directory, it is the entry that matched."
  [entries {:caller/keys [working-dir] :as search} script-name]
  (if working-dir
    (when-let [entry (working-dir-entry entries search script-name)]
      {:target/window-id (:windowId entry)
       :target/entry entry})
    (window-target entries search script-name)))

(defn settled-without-window?
  "True when waiting can't bring a window: not under an extension host, or under one
   that isn't among the ancestors, and no live entry for the variable's window or the
   working directory."
  [entries {:caller/keys [extension-host-pid] :as search} script-name]
  (and (nil? extension-host-pid)
       (nil? (target entries search script-name))))

(defn caller-window-port+
  "Promise of `{:port … :host …}` for the wrapper's own server in the caller's window: the
   port read from the entry's port file with `read-port+`, and the entry's host. Nil when
   there is no entry or port yet."
  [entries search script-name read-port+]
  (let [{:keys [portFilePath host]} (get-in (target entries search script-name) [:target/entry :mcp])]
    (if portFilePath
      (.then (read-port+ portFilePath)
             (fn [port]
               (when port
                 {:port port :host host})))
      (js/Promise.resolve nil))))

(defn- window-line
  [folder-key {:keys [serverName windowId] :as entry}]
  (string/join "  " [serverName windowId (or (get entry folder-key) "no folder")]))

(defn failure-message
  "Why the wrapper found nothing to connect to, with the live windows of its server, by
   first folder when the working directory looked for one."
  [entries {:caller/keys [working-dir] :as search} script-name]
  (let [own (filterv #(own-server? script-name %) entries)
        server (or (:serverName (first own)) script-name)
        folder-key (if working-dir :workspaceFolder :workspaceRoot)]
    (if-let [window-id (:target/window-id (target entries search script-name))]
      (str "Found the window " window-id ", but no live " server " entry in it.")
      (str "No window found for " server ". Looked for " (caller-search-text search)
           ". Give the wrapper the port file of the window to connect to."
           " An ECA entry the extension manages is rewritten on every start;"
           " the extension's ECA auto-registration setting turns that off. Live windows:\n"
           (string/join "\n" (map #(window-line folder-key %) own))))))

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

(def ^:private !resolved-paths
  (atom {}))

(defn cached-real-path
  "`real-path`, with a path that resolves remembered for the life of the process. A path
   that doesn't resolve, such as a folder that isn't reachable yet, is tried again."
  [p]
  (or (get @!resolved-paths p)
      (if-let [resolved (resolved-path p)]
        (do (swap! !resolved-paths assoc p resolved)
            resolved)
        (path/resolve p))))

(defn read-caller-entries
  "Live entries to find the caller's window by `search` in, with the real folders
   `with-real-folders` gives the `script-name` server's entries."
  [search script-name]
  (with-real-folders (read-live-entries) search script-name cached-real-path))

(defn- real-working-dir
  "This process's working directory as a real path, or nil when it is gone."
  []
  (try
    (real-path (js/process.cwd))
    (catch :default _ nil)))

(defn current-caller-search
  "`caller-search` for this process."
  []
  (caller-search (delay (ancestry (read-process-table) js/process.pid))
                 (into {} (map vec) (js/Object.entries (.-env js/process)))
                 {:caller/working-dir (real-working-dir)
                  :caller/platform js/process.platform}))
