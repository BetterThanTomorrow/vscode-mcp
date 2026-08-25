(ns vscode-mcp.registry
  "Window-shard schema, paths, and atomic disk writes."
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [vscode-mcp.wrapper-install :as wrapper-install]))

(def schema-version 1)

(def core-keys
  #{:schemaVersion :name :serverName :windowId
    :workspaceRoot :hostname :pid :updatedAt :mcp})

(def default-heartbeat-ms 30000)
(def default-debounce-ms 1000)

(defn shard-name
  [server-name window-id]
  (str server-name "-" window-id))

(defn shard-filename
  [server-name window-id]
  (str (shard-name server-name window-id) ".json"))

(defn default-dir
  []
  (path/join (os/homedir) ".config" "vscode-mcp" "registry" "windows"))

(defn registry-dir
  [config]
  (or (:registry/dir config) (default-dir)))

(defn shard-path
  [config server-name window-id]
  (path/join (registry-dir config) (shard-filename server-name window-id)))

(defn now-iso
  []
  (.toISOString (js/Date.)))

(defn current-hostname
  []
  (os/hostname))

(defn current-pid
  []
  js/process.pid)

(defn pid-alive?
  [process-id]
  (try
    (js/process.kill process-id 0)
    true
    (catch :default _
      false)))

(defn port-file-path
  [server-info]
  (some-> (:server/port-file-uri server-info)
          (unchecked-get "fsPath")))

(defn mcp-info
  "MCP attach fields when `server-info` has an assigned port, otherwise nil."
  [config server-info]
  (when-let [port (:server/assigned-port server-info)]
    (cond-> {:host (:server/host server-info)
             :port port
             :portFilePath (port-file-path server-info)}
      (:lifecycle/wrapper-install-dir config)
      (assoc :wrapperPath (wrapper-install/installed-path
                           (:lifecycle/wrapper-install-dir config)
                           (:cursor/script-relative-path config))))))

(defn build-envelope
  [{:keys [server-name window-id workspace-root hostname pid updated-at mcp]}]
  (cond-> {:schemaVersion schema-version
           :name (shard-name server-name window-id)
           :serverName server-name
           :windowId window-id
           :hostname hostname
           :pid pid
           :updatedAt updated-at}
    workspace-root (assoc :workspaceRoot workspace-root)
    mcp (assoc :mcp mcp)))

(defn merge-custom-data
  "Merges `custom-data` onto `envelope`, dropping keys that would overwrite the core envelope."
  [envelope custom-data]
  (if (map? custom-data)
    (merge envelope (apply dissoc custom-data core-keys))
    envelope))

(defn envelope-for
  [config server-info custom-data]
  (let [envelope (build-envelope
                  {:server-name (:cursor/server-name config)
                   :window-id (:server/instance-slug server-info)
                   :workspace-root (:server/workspace-root server-info)
                   :hostname (current-hostname)
                   :pid (current-pid)
                   :updated-at (now-iso)
                   :mcp (mcp-info config server-info)})]
    (merge-custom-data envelope custom-data)))

(defn- rename-over!
  [tmp dest]
  (try
    (fs/renameSync tmp dest)
    (catch :default e
      (let [code (.-code e)]
        (if (or (= "EPERM" code) (= "EBUSY" code))
          (do
            (try (fs/unlinkSync dest) (catch :default _))
            (fs/renameSync tmp dest))
          (throw e))))))

(defn atomic-write!
  "Writes `payload` as JSON to `dest-path` via a same-directory temp file and rename."
  [dest-path payload]
  (let [dir (path/dirname dest-path)
        tmp (str dest-path "." (current-pid) "." (rand-int 1000000000) ".tmp")
        json (js/JSON.stringify (clj->js payload) nil 2)]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync tmp json)
    (rename-over! tmp dest-path)))

(defn unlink-silent!
  [file-path]
  (try
    (when (fs/existsSync file-path)
      (fs/unlinkSync file-path))
    (catch :default _)))

(defn- parse-json-file
  [file-path]
  (try
    (js->clj (js/JSON.parse (fs/readFileSync file-path "utf8"))
             :keywordize-keys true)
    (catch :default _
      nil)))

(defn- tmp-pid
  "Pid encoded in `<shard>.json.<pid>.<rand>.tmp`, or nil."
  [filename]
  (when-let [m (re-find #"\.(\d+)\.\d+\.tmp$" filename)]
    (js/parseInt (second m) 10)))

(defn- unlink-if-dead-json!
  [file-path]
  (when-let [doc (parse-json-file file-path)]
    (when-let [file-pid (:pid doc)]
      (when-not (pid-alive? file-pid)
        (unlink-silent! file-path)))))

(defn- unlink-if-dead-tmp!
  [file-path filename]
  (when-let [file-pid (tmp-pid filename)]
    (when-not (pid-alive? file-pid)
      (unlink-silent! file-path))))

(defn sweep-dead-pid-files!
  "Unlinks json shards and leftover tmp files whose pid is not running."
  [dir]
  (when (fs/existsSync dir)
    (doseq [filename (array-seq (fs/readdirSync dir))]
      (let [file-path (path/join dir filename)]
        (cond
          (re-find #"\.json$" filename) (unlink-if-dead-json! file-path)
          (re-find #"\.tmp$" filename) (unlink-if-dead-tmp! file-path filename))))))
