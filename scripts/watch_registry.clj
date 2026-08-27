(ns watch-registry
  "Consumer-view watcher for ~/.config/vscode-mcp/registry/windows.
   Logs appear/gone, MCP attach, and session/runtime changes. Ignores heartbeats."
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as string]))

(def default-ttl-ms
  60000)

(def default-poll-ms
  1000)

(def cli-spec
  {:coerce {:dir :string
            :log :string
            :ttl-ms :long
            :poll-ms :long
            :verbose :boolean}
   :alias {:d :dir
           :l :log
           :v :verbose}})

(defn default-registry-dir
  []
  (str (fs/path (fs/home) ".config" "vscode-mcp" "registry" "windows")))

(defn default-log-file
  []
  (str (fs/path ".tmp" "registry-watch.log")))

(defn pid-alive?
  [pid]
  (boolean (some-> pid
                   long
                   java.lang.ProcessHandle/of
                   (.orElse nil)
                   (.isAlive))))

(defn age-ms
  [iso]
  (when iso
    (- (System/currentTimeMillis)
       (.toEpochMilli (java.time.Instant/parse iso)))))

(defn live?
  [shard ttl-ms]
  (boolean
   (and shard
        (pid-alive? (:pid shard))
        (when-let [age (age-ms (:updatedAt shard))]
          (< age ttl-ms)))))

(defn session-id
  [session]
  [(:replSessionKey session) (:projectRoot session)])

(defn compact-runtime
  [runtime]
  (when runtime
    (select-keys runtime [:runtimeId :description :buildId :host])))

(defn compact-build
  [build]
  (cond-> (select-keys build [:buildId :isActive :isHumansActiveRuntime :runtimeCount])
    (:mostRecentRuntime build)
    (assoc :mostRecentRuntime (compact-runtime (:mostRecentRuntime build)))))

(defn compact-session
  [session]
  (let [builds (mapv compact-build (:builds session))]
    (cond-> (select-keys session [:replSessionKey :projectRoot :globs :supportsRuntimes])
      (seq builds) (assoc :builds builds))))

(defn consumer-view
  [shard]
  (when shard
    (-> shard
        (select-keys [:name :serverName :windowId :workspaceRoot :hostname :pid :mcp])
        (assoc :sessions (mapv compact-session (:sessions shard))))))

(defn session-index
  [view]
  (into {} (map (juxt session-id identity) (:sessions view))))

(defn mcp-endpoint
  [mcp]
  (when mcp
    (str (:host mcp) ":" (:port mcp))))

(defn with-identity
  [view event]
  (merge (select-keys view [:name :serverName :windowId :workspaceRoot])
         event))

(defn session-events
  [prev-view next-view]
  (let [prev-idx (session-index prev-view)
        next-idx (session-index next-view)
        ids (set (concat (keys prev-idx) (keys next-idx)))]
    (into []
          (keep (fn [id]
                  (let [before (get prev-idx id)
                        after (get next-idx id)]
                    (cond
                      (nil? before) {:op :session+
                                     :session after}
                      (nil? after) {:op :session-
                                    :session before}
                      (not= before after) {:op :session*
                                           :session after
                                           :from before}))))
          ids)))

(defn events-for-name
  [prev next-snap]
  (let [was-live (boolean (:live? prev))
        is-live (boolean (:live? next-snap))
        prev-view (:view prev)
        next-view (:view next-snap)
        ident #(with-identity (or next-view prev-view) %)]
    (mapv ident
          (cond
            (and (not was-live) is-live)
            (into [{:op :appeared
                    :pid (:pid next-view)
                    :mcp (:mcp next-view)}]
                  (session-events nil next-view))

            (and was-live (not is-live))
            [{:op :gone
              :reason (if next-snap :stale :deleted)
              :pid (:pid (or next-view prev-view))}]

            (and was-live is-live)
            (let [mcp-before (mcp-endpoint (:mcp prev-view))
                  mcp-after (mcp-endpoint (:mcp next-view))
                  mcp-event (when (not= mcp-before mcp-after)
                              {:op :mcp
                               :from mcp-before
                               :to mcp-after})]
              (cond-> (session-events prev-view next-view)
                mcp-event (into [mcp-event])))

            :else []))))

(defn shard-paths
  [dir]
  (->> (fs/glob dir "*.json")
       (map str)
       (remove #(string/ends-with? % ".tmp"))
       vec))

(defn read-shard
  [path]
  (try
    (json/parse-string (slurp path) true)
    (catch Exception _
      nil)))

(defn snapshot-entry
  [path ttl-ms]
  (when-let [shard (read-shard path)]
    (let [view (consumer-view shard)]
      [(:name view (fs/file-name path))
       {:live? (live? shard ttl-ms)
        :view view
        :path path}])))

(defn snapshot-dir
  [dir ttl-ms]
  (into {} (keep #(snapshot-entry % ttl-ms) (shard-paths dir))))

(defn tick
  [prev next-snap]
  {:events (vec (mapcat (fn [shard-name]
                          (events-for-name (get prev shard-name)
                                           (get next-snap shard-name)))
                        (set (concat (keys prev) (keys next-snap)))))
   :next next-snap})

(defn event-lines
  [event]
  (letfn [(cell [x]
            (if (or (nil? x) (= x ""))
              "-"
              (str x)))
          (rid [b]
            (get-in b [:mostRecentRuntime :runtimeId]))
          (interesting? [b]
            (or (:isHumansActiveRuntime b)
                (pos? (:runtimeCount b 0))))
          (sig [b]
            [(:buildId b) (:isHumansActiveRuntime b) (:runtimeCount b) (rid b)])
          (row [{:keys [session build rt project]}]
            (let [ts (.format (java.time.LocalTime/now)
                              (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss"))
                  server (:name event)
                  op (:op event)
                  event-type (if-let [reason (:reason event)]
                               (str (name op) "/" (name reason))
                               (name op))]
              (str (string/join "  " (map cell [ts server event-type session build rt
                                                (or project (:workspaceRoot event))]))
                   "\n")))
          (session-rows [session builds]
            (if (seq builds)
              (map (fn [b]
                     (row {:session (:replSessionKey session)
                           :build (:buildId b)
                           :rt (rid b)
                           :project (:projectRoot session)}))
                   builds)
              [(row {:session (:replSessionKey session)
                     :project (:projectRoot session)})]))]
    (let [op (:op event)
          session (:session event)]
      (case op
        (:appeared :gone :watching)
        [(row {})]

        :mcp
        [(row {:rt (:to event)})]

        (:session+ :session-)
        (session-rows session (filter interesting? (:builds session)))

        :session*
        (let [prev (into {} (map (juxt :buildId identity) (:builds (:from event))))
              changed (filterv (fn [b]
                                 (not= (sig (get prev (:buildId b)))
                                       (sig b)))
                               (:builds session))]
          (session-rows session changed))

        [(row {})]))))

(defn emit!
  ([log-file event]
   (emit! log-file event false))
  ([log-file event verbose?]
   (doseq [line (if verbose?
                  [(str (pr-str (assoc event :at (str (java.time.Instant/now)))) "\n")]
                  (event-lines event))]
     (print line)
     (flush)
     (when log-file
       (spit log-file line :append true)))))

(defn resolve-opts
  [opts]
  {:dir (or (:dir opts) (default-registry-dir))
   :log-file (or (:log opts) (default-log-file))
   :ttl-ms (or (:ttl-ms opts) default-ttl-ms)
   :poll-ms (or (:poll-ms opts) default-poll-ms)
   :verbose? (boolean (:verbose opts))})

(defn watch-loop!
  [{:keys [dir log-file ttl-ms poll-ms verbose?]} running?]
  (loop [prev {}]
    (when @running?
      (let [{:keys [events next]} (tick prev (snapshot-dir dir ttl-ms))]
        (run! #(emit! log-file % verbose?) events)
        (Thread/sleep poll-ms)
        (recur next)))))

(defn start!
  "Starts a background consumer watcher. Returns a handle for `stop!`."
  [opts]
  (let [{:keys [dir log-file verbose?] :as resolved} (resolve-opts opts)
        running? (atom true)]
    (fs/create-dirs dir)
    (fs/create-dirs (fs/parent log-file))
    (spit log-file "")
    (println (str "Watching " dir))
    (println (str "Logging to " (fs/absolutize log-file)
                  (if verbose? " (verbose)" "")))
    (emit! log-file {:op :watching
                     :dir dir
                     :ttl-ms (:ttl-ms resolved)
                     :poll-ms (:poll-ms resolved)}
           verbose?)
    {:running? running?
     :opts resolved
     :future (future (watch-loop! resolved running?))}))

(defn stop!
  [handle]
  (when-let [running? (:running? handle)]
    (reset! running? false))
  (when-let [fut (:future handle)]
    (deref fut (+ default-poll-ms 500) :timeout))
  :stopped)

(defn watch-until-interrupt!
  "Blocking watch for CLI / bb task use."
  [opts]
  (let [handle (start! opts)]
    (try
      (deref (:future handle))
      (finally
        (stop! handle)))))

(defn main!
  [args]
  (watch-until-interrupt! (cli/parse-opts args cli-spec)))

(when (= *file* (System/getProperty "babashka.file"))
  (main! *command-line-args*))
