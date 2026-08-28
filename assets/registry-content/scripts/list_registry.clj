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
  [shard]
  (boolean
   (and shard
        (pid-alive? (:pid shard))
        (when-let [age (age-ms (:updatedAt shard))]
          (< age ttl-ms)))))

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
  "Provider keys on `shard` that are not the core envelope or `sessions`."
  [shard]
  (not-empty (apply dissoc shard envelope-keys)))

(defn- parent-path
  "Directory of `path`, keeping the original separator."
  [path]
  (let [idx (max (.lastIndexOf path "/") (.lastIndexOf path "\\"))]
    (when-not (neg? idx)
      (subs path 0 idx))))

(defn session-rel-root
  "Directory used to relativize session projectRoot. First folder if present,
   else parent of a .code-workspace path, else workspaceRoot."
  [shard]
  (or (not-empty (:workspaceFolder shard))
      (when-let [root (:workspaceRoot shard)]
        (if (string/ends-with? root ".code-workspace")
          (or (parent-path root) root)
          root))))

(defn window-snapshot
  [shard]
  (let [ws (:workspaceRoot shard)
        sessions (mapv #(session-snapshot % (session-rel-root shard)) (:sessions shard))
        extra (extra-discovery shard)]
    (cond-> {:serverName (:serverName shard)
             :windowId (:windowId shard)
             :hostname (:hostname shard)
             :ageMs (age-ms (:updatedAt shard))}
      (:appId shard) (assoc :appId (:appId shard))
      ws (assoc :workspaceRoot ws)
      (:mcp shard) (assoc :mcp (select-keys (:mcp shard)
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
  [mcp]
  (if mcp
    (str "  mcp:  node "
         (shorten-home (:wrapperPath mcp)) " "
         (shorten-home (:portFilePath mcp)) " "
         (:host mcp))
    "  mcp:  (not assigned)"))

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

(defn shard-paths
  [dir]
  (->> (fs/glob dir "*.json")
       (map str)
       (remove #(string/ends-with? % ".tmp"))
       sort
       vec))

(defn read-shard
  [path]
  (try
    (json/parse-string (slurp path) true)
    (catch Exception _
      nil)))

(defn windows-dir
  [opts]
  (or (:dir opts)
      (str (fs/path (fs/cwd) "windows"))))

(defn list-snapshots
  [dir include-stale?]
  (->> (shard-paths dir)
       (keep read-shard)
       (filter (if include-stale? identity live?))
       (map window-snapshot)
       (sort-by (juxt :serverName :windowId))
       vec))

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

(defn main!
  [args]
  (let [opts (cli/parse-opts args cli-spec)
        snaps (list-snapshots (windows-dir opts) (boolean (:stale opts)))]
    (emit! (render snaps opts))))

(when (= *file* (System/getProperty "babashka.file"))
  (main! *command-line-args*))
