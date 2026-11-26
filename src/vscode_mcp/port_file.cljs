(ns vscode-mcp.port-file
  "MCP port file under ~/.config/vscode-mcp/port-files.

   Path: <dir>/<serverName>-<windowId>.port
   Exists while Running (create after listen, delete on stop).
   Stale sweep on activate removes .port files whose port is not accepting."
  (:require
   ["fs" :as fs]
   ["net" :as net]
   ["os" :as os]
   ["path" :as path]
   [clojure.string :as string]
   [vscode-mcp.registry :as registry]
   [vscode-mcp.stdio-config :as stdio-config]))

(defn default-dir
  "Returns ~/.config/vscode-mcp/port-files."
  []
  (path/join (os/homedir) ".config" "vscode-mcp" "port-files"))

(defn port-dir
  "Returns `:port-file/dir` from `config`, or `default-dir`."
  [config]
  (or (:port-file/dir config) (default-dir)))

(defn filename
  "Returns `<serverName>-<windowId>.port`."
  [server-name window-id]
  (str (registry/entry-name server-name window-id) ".port"))

(defn primary-path
  "Absolute port-file path for `server-name` and `window-id`."
  ([server-name window-id]
   (primary-path nil server-name window-id))
  ([config server-name window-id]
   (path/join (port-dir config) (filename server-name window-id))))

(defn- parse-port-digits
  [file-path]
  (try
    (let [raw (string/trim (str (fs/readFileSync file-path "utf8")))
          port (js/parseInt raw 10)]
      (when (and (re-matches #"\d+" raw)
                 (pos? port)
                 (<= port 65535))
        port))
    (catch :default _
      nil)))

(defn- unlink-quiet!
  [file-path]
  (try
    (fs/unlinkSync file-path)
    (catch :default _)))

(defn- port-accepting?+
  "Resolves true when `host`:`port` accepts a TCP connection within 200ms."
  [host port]
  (js/Promise.
   (fn [resolve-fn _reject]
     (let [socket (net/connect #js {:host host :port port})
           done? (atom false)
           finish! (fn [ok?]
                     (when (compare-and-set! done? false true)
                       (try (.destroy socket) (catch :default _))
                       (resolve-fn ok?)))]
       (.setTimeout socket 200)
       (.once socket "connect" #(finish! true))
       (.once socket "timeout" #(finish! false))
       (.once socket "error" #(finish! false))))))

(defn- ^:async sweep-one!+
  [file-path host]
  (if-let [port (parse-port-digits file-path)]
    (let [ok? (await (port-accepting?+ host port))]
      (when-not ok?
        (unlink-quiet! file-path))
      (not ok?))
    (do (unlink-quiet! file-path)
        true)))

(defn ^:async sweep-stale!+
  "Deletes `.port` files in `dir` whose port is not accepting (or content is invalid).
   Missing `dir` is a no-op. Returns a promise of deleted count."
  ([dir]
   (await (sweep-stale!+ dir stdio-config/default-host)))
  ([dir host]
   (if-not (fs/existsSync dir)
     0
     (let [paths (->> (array-seq (fs/readdirSync dir))
                      (filter #(re-find #"\.port$" %))
                      (map #(path/join dir %)))
           results (await (js/Promise.all (into-array (mapv #(sweep-one!+ % host) paths))))]
       (count (filter true? results))))))
