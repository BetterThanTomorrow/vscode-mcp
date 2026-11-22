(ns list-registry
  "Installed `bb list`: live MCP windows for attach."
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.pprint :as pprint]
   [clojure.string :as string]))

(def ttl-ms
  60000)

(def envelope-keys
  #{:schemaVersion :name :serverName :windowId :appId :workspaceRoot :workspaceFolder
    :hostname :pid :updatedAt :mcp :sessions})

(def snapshot-keys
  #{:serverName :windowId :appId :workspaceRoot :hostname :ageMs :mcp :sessions})

(def cli-spec
  {:coerce {:json :boolean
            :edn :boolean
            :stale :boolean
            :all :boolean
            :dir :string}
   :alias {:d :dir}})

(defn pid-alive?
  [pid]
  (boolean (some-> pid
                   long
                   java.lang.ProcessHandle/of
                   (.orElse nil)
                   (.isAlive))))

(defn age-ms
  "Milliseconds since `iso` instant, or nil when `iso` is missing."
  [iso]
  (when iso
    (- (System/currentTimeMillis)
       (.toEpochMilli (java.time.Instant/parse iso)))))

(defn live?
  "True when `pid` is running and `updatedAt` is younger than `ttl-ms`."
  [entry]
  (boolean
   (and entry
        (pid-alive? (:pid entry))
        (when-let [age (age-ms (:updatedAt entry))]
          (< age ttl-ms)))))

;; The caller's window

(def window-id-env
  "Environment variable vscode-mcp sets in a window's terminals to the window's windowId."
  "VSCODE_MCP_WINDOW_ID")

(defn ancestry
  "[pid parent-pid] pairs from this process up, nearest first."
  []
  (->> (java.lang.ProcessHandle/current)
       (iterate #(some-> ^java.lang.ProcessHandle % .parent (.orElse nil)))
       (take-while some?)
       (mapv (fn [^java.lang.ProcessHandle ph]
               [(.pid ph) (some-> (.parent ph) (.orElse nil) .pid)]))))

(defn caller-search
  "What identifies the caller's window. Under an extension host, which VS Code marks
   in the environment it passes down, the extension host is the nearest ancestor whose
   parent is VS Code's main process, `VSCODE_PID`. Otherwise it is the window
   `VSCODE_MCP_WINDOW_ID` names."
  [ancestry env]
  (if (= "extensionHost" (get env "VSCODE_CRASH_REPORTER_PROCESS_TYPE"))
    (let [vscode-pid (some-> (get env "VSCODE_PID") parse-long)]
      {:caller/vscode-pid vscode-pid
       :caller/extension-host-pid (when vscode-pid
                                    (some (fn [[pid ppid]]
                                            (when (= vscode-pid ppid) pid))
                                          ancestry))})
    {:caller/env-window-id (get env window-id-env)}))

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
    env-window-id (str "the window " window-id-env " names, " env-window-id)
    :else (str "a VS Code extension host among this process's ancestors, or "
               window-id-env ", and there is neither")))

(defn current-caller-search
  "`caller-search` for this process."
  []
  (caller-search (ancestry) (into {} (System/getenv))))

(defn project-root-display
  "`.` when `project-root` is the workspace, a relative path when it is under
   `workspace-root`, otherwise the absolute path."
  [project-root workspace-root]
  (cond
    (nil? project-root) nil
    (nil? workspace-root) (str project-root)
    :else
    (let [ws (str (fs/absolutize workspace-root))
          pr (str (fs/absolutize project-root))]
      (cond
        (= pr ws) "."
        (string/starts-with? pr (str ws fs/file-separator))
        (str (fs/relativize ws pr))
        :else pr))))

(defn compact-builds
  "Builds that have a most-recent runtime: `buildId`, `runtimeId`, `description`."
  [session]
  (->> (:builds session)
       (keep (fn [b]
               (when-let [rt (:mostRecentRuntime b)]
                 {:buildId (:buildId b)
                  :runtimeId (:runtimeId rt)
                  :description (:description rt)})))
       vec))

(defn session-snapshot
  [session workspace-root]
  (let [root (:projectRoot session)
        builds (compact-builds session)]
    (cond-> {:replSessionKey (:replSessionKey session)
             :projectRoot root
             :projectRootDisplay (project-root-display root workspace-root)}
      (seq builds) (assoc :builds builds))))

(defn extra-discovery
  "Provider keys on `entry` that are not the core envelope or `sessions`."
  [entry]
  (not-empty (apply dissoc entry envelope-keys)))

(defn- parent-path
  "Directory of `path`, keeping the original separator."
  [path]
  (let [idx (max (.lastIndexOf path "/") (.lastIndexOf path "\\"))]
    (when-not (neg? idx)
      (subs path 0 idx))))

(defn session-rel-root
  "Directory used to relativize session projectRoot. First folder if present,
   else parent of a .code-workspace path, else workspaceRoot."
  [entry]
  (or (not-empty (:workspaceFolder entry))
      (when-let [root (:workspaceRoot entry)]
        (if (string/ends-with? root ".code-workspace")
          (or (parent-path root) root)
          root))))

(defn window-snapshot
  [entry]
  (let [ws (:workspaceRoot entry)
        sessions (mapv #(session-snapshot % (session-rel-root entry)) (:sessions entry))
        extra (extra-discovery entry)]
    (cond-> {:serverName (:serverName entry)
             :windowId (:windowId entry)
             :hostname (:hostname entry)
             :ageMs (age-ms (:updatedAt entry))}
      (:appId entry) (assoc :appId (:appId entry))
      ws (assoc :workspaceRoot ws)
      (:mcp entry) (assoc :mcp (select-keys (:mcp entry)
                                           [:host :port :wrapperPath :portFilePath]))
      (seq sessions) (assoc :sessions sessions)
      extra (merge extra))))

(defn extra-from-snap
  [snap]
  (not-empty (apply dissoc snap snapshot-keys)))

(defn shorten-home
  [s]
  (let [home (str (fs/home))]
    (if (and s (string/starts-with? s home))
      (str "~" (subs s (count home)))
      s)))

(defn format-build
  [{:keys [buildId runtimeId description]}]
  (str buildId " #" runtimeId " \"" description "\""))

(defn format-mcp-line
  "The command that attaches to the window: the wrapper, and the port file and host
   when the snapshot names them."
  [mcp]
  (cond
    (nil? mcp) "  mcp:  (not assigned)"
    (:portFilePath mcp) (str "  mcp:  node "
                             (shorten-home (:wrapperPath mcp)) " "
                             (shorten-home (:portFilePath mcp)) " "
                             (:host mcp))
    :else (str "  mcp:  node " (shorten-home (:wrapperPath mcp)))))

(defn format-session-line
  [session key-width]
  (let [builds (:builds session)
        build-str (when (seq builds)
                    (string/join ",  " (map format-build builds)))]
    (str "    "
         (format (str "%-" key-width "s") (:replSessionKey session))
         " "
         (:projectRootDisplay session)
         (when build-str (str "  " build-str)))))

(defn format-extra-line
  [[k v]]
  (str "  " (name k) ": " (pr-str v)))

(defn format-window-text
  [snap]
  (let [header (string/join "  " (filter some? [(:serverName snap)
                                                (:windowId snap)
                                                (:appId snap)
                                                (or (:workspaceRoot snap) "no folder")]))
        age-s (quot (:ageMs snap 0) 1000)
        sessions (:sessions snap)
        key-width (if (seq sessions)
                    (apply max (map (comp count :replSessionKey) sessions))
                    0)]
    (string/join
     "\n"
     (concat
      [header
       (str "  host: " (:hostname snap) "  age " age-s "s")
       (format-mcp-line (:mcp snap))]
      (when (seq sessions)
        (cons "  sessions:"
              (map #(format-session-line % key-width) sessions)))
      (map format-extra-line (extra-from-snap snap))))))

(defn entry-paths
  [dir]
  (->> (fs/glob dir "*.json")
       (map str)
       (remove #(string/ends-with? % ".tmp"))
       sort
       vec))

(defn read-entry
  [path]
  (try
    (json/parse-string (slurp path) true)
    (catch Exception _
      nil)))

(defn windows-dir
  [opts]
  (or (:dir opts)
      (str (fs/path (fs/cwd) "windows"))))

(defn read-entries
  "Entries under `dir`: the live ones, or all of them when `include-stale?`."
  [dir include-stale?]
  (->> (entry-paths dir)
       (keep read-entry)
       (filter (if include-stale? identity live?))
       vec))

(defn snapshots
  [entries]
  (->> entries
       (map window-snapshot)
       (sort-by (juxt :serverName :windowId))
       vec))

(defn caller-window-snapshot
  "`snap` as the caller's window shows it: `mcp` holds only the wrapper, since the
   wrapper with no port file finds the window it runs in."
  [snap]
  (cond-> snap
    (:mcp snap) (update :mcp select-keys [:wrapperPath])))

(defn caller-listing
  "The `entries` `bb list` shows, the caller's window when it shows only that one, and
   how many other live windows it leaves out. Inside a window it shows only the
   caller's window, unless `all?`."
  [{:keys [entries search all?]}]
  (let [live (filter live? entries)
        own (when-not all?
              (caller-window-id live search))]
    (if own
      {:entries (filterv #(= own (:windowId %)) entries)
       :caller-window-id own
       :other-window-count (->> live (map :windowId) (remove #{own}) distinct count)}
      {:entries entries
       :other-window-count 0})))

(defn render
  [snapshots opts]
  (cond
    (:json opts) (json/generate-string snapshots {:pretty true})
    (:edn opts) (with-out-str (pprint/pprint snapshots))
    :else (string/join "\n\n" (map format-window-text snapshots))))

(defn emit!
  [s]
  (when (seq s)
    (println s)))

(defn other-windows-line
  "The note about the live windows `bb list` left out, or nil when there are none."
  [n]
  (when (pos? n)
    (str n " other live " (if (= 1 n) "window" "windows") ". `bb list --all` lists them.")))

(defn main!
  [args]
  (let [opts (cli/parse-opts args cli-spec)
        {:keys [entries caller-window-id other-window-count]}
        (caller-listing {:entries (read-entries (windows-dir opts) (boolean (:stale opts)))
                         :search (current-caller-search)
                         :all? (boolean (:all opts))})
        note (other-windows-line other-window-count)]
    (emit! (render (cond->> (snapshots entries)
                     caller-window-id (mapv caller-window-snapshot))
                   opts))
    (when note
      (if (or (:json opts) (:edn opts))
        (binding [*out* *err*]
          (println note))
        (println (str "\n" note))))))

(when (= *file* (System/getProperty "babashka.file"))
  (main! *command-line-args*))
