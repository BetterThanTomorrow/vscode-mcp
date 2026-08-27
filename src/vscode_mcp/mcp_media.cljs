(ns vscode-mcp.mcp-media
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [vscode-mcp.registry :as registry]))

(def delay-ms 600000)
(def interval-ms 60000)
(def age-ms 600000)

(defonce !media (atom {}))

(defn media-dir
  "Returns the mcp-media directory for shard `server-name`-`window-id`."
  [config server-name window-id]
  (let [registry-home (path/dirname (registry/registry-dir config))]
    (path/join (path/dirname registry-home) "mcp-media" (registry/shard-name server-name window-id))))

(defn- file-stale?
  [file-path now-ms age]
  (try
    (let [st (fs/statSync file-path)]
      (and (.isFile st)
           (< (.-mtimeMs st) (- now-ms age))))
    (catch :default _
      false)))

(defn stale-files
  "Returns file paths in `dir` whose mtime is older than `age` at `now-ms`.
   A missing `dir` is an empty vector."
  [dir now-ms age]
  (if-not (fs/existsSync dir)
    []
    (->> (array-seq (fs/readdirSync dir))
         (map (fn [filename]
                (path/join dir filename)))
         (filter (fn [file-path]
                   (file-stale? file-path now-ms age)))
         vec)))

(defn- unlink-quiet!
  [file-path]
  (try
    (fs/unlinkSync file-path)
    (catch :default _)))

(defn sweep!
  "Deletes files in `dir` whose mtime is older than `age` at `now-ms`. Missing paths are ignored."
  [dir now-ms age]
  (doseq [file-path (stale-files dir now-ms age)]
    (unlink-quiet! file-path)))

(defn- media-key
  [config server-info]
  [(:cursor/server-name config) (:server/instance-slug server-info)])

(defn- find-key
  [server-name]
  (some (fn [k]
          (when (= server-name (first k))
            k))
        (keys @!media)))

(defn- clear-entry-timers!
  [{:keys [delay interval clear-timeout clear-interval]}]
  (when (and delay clear-timeout)
    (clear-timeout delay))
  (when (and interval clear-interval)
    (clear-interval interval)))

(defn clear-media-state!
  "Test helper: clears timers and drops all media entries."
  []
  (doseq [[_ entry] @!media]
    (clear-entry-timers! entry))
  (reset! !media {}))

(defn- current-now [ctx]
  (let [v (or (:now-ms ctx) js/Date.now)]
    (if (fn? v)
      (v)
      v)))

(defn- timeout-fn [ctx]
  (or (:set-timeout ctx) js/setTimeout))

(defn- interval-fn [ctx]
  (or (:set-interval ctx) js/setInterval))

(defn- clear-timeout-fn [ctx]
  (or (:clear-timeout ctx) js/clearTimeout))

(defn- clear-interval-fn [ctx]
  (or (:clear-interval ctx) js/clearInterval))

(defn- sweep-window!
  [ctx dir]
  (sweep! dir (current-now ctx) age-ms))

(defn on-stopping!
  "Clears the delay and interval for this window. Leaves the media directory."
  [{:keys [config server-info]}]
  (when-let [key (or (when (and config server-info)
                       (media-key config server-info))
                     (find-key (:cursor/server-name config)))]
    (when-let [entry (get @!media key)]
      (clear-entry-timers! entry)
      (swap! !media dissoc key))))

(defn- schedule-sweeps!
  [ctx key dir]
  (let [schedule-timeout (timeout-fn ctx)
        schedule-interval (interval-fn ctx)
        clear-timeout (clear-timeout-fn ctx)
        clear-interval (clear-interval-fn ctx)
        delay-id (schedule-timeout
                  (fn []
                    (sweep-window! ctx dir)
                    (let [interval-id (schedule-interval #(sweep-window! ctx dir) interval-ms)]
                      (swap! !media assoc-in [key :interval] interval-id)))
                  delay-ms)]
    (swap! !media assoc key {:delay delay-id
                             :interval nil
                             :clear-timeout clear-timeout
                             :clear-interval clear-interval})))

(defn on-started!
  "Waits 10 minutes, then sweeps the window media dir every 60 seconds while running."
  [{:keys [config server-info] :as ctx}]
  (when (and (:cursor/server-name config)
             (:server/instance-slug server-info))
    (let [key (media-key config server-info)]
      (when-let [existing (get @!media key)]
        (clear-entry-timers! existing))
      (schedule-sweeps! ctx key
                        (media-dir config
                                   (:cursor/server-name config)
                                   (:server/instance-slug server-info))))))
