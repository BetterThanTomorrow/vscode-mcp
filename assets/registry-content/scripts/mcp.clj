(ns mcp
  "Installed `bb mcp`: one JSON-RPC request to a live window socket, or a `--readme` briefing."
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as string]
   [list-registry :as listing]
   [mcp-briefing :as briefing])
  (:import
    [java.io BufferedReader InputStreamReader]
    [java.net InetSocketAddress Socket]
    [java.util Base64]))

(def cli-opts
  {:spec {:server-name {:desc "Shard serverName from `bb list`"}
          :window-id {:desc "Shard windowId from `bb list`"}
          :name {:desc "Tool name (`tools/call`)"}
          :uri {:desc "Resource URI (`resources/read`)"}
          :args {:desc "Tool arguments: `-` reads JSON from stdin"}
          :timeout {:coerce :long
                    :default 180
                    :desc "Seconds to wait for a JSON-RPC response (0 = no deadline)"}
          :readme {:coerce :boolean
                   :desc "First-job briefing (server, skills, tools)"}
          :readme-tool {:desc "Inspect one tool by name (description + inputSchema)"}
          :help {:coerce :boolean
                 :alias :h
                 :desc "Print usage as a JSON result"}
          :hhelp {:coerce :boolean
                  :desc "Plain-text CLI usage"}
          :hreadme {:coerce :boolean
                    :desc "Same briefing as --readme, as plain text"}
          :hreadme-tool {:coerce :boolean
                         :desc "Plain-text --readme-tool help"}
          :dir {:coerce :string
                :alias :d
                :desc "Windows directory"}}
   :restrict true})

(def verbs
  #{"initialize" "tools/list" "tools/call" "resources/list"
    "resources/templates/list" "resources/read" "ping"})

(defn- fail
  ([ctx code message]
   (fail ctx code message nil))
  ([ctx code message extra]
   (assoc ctx
          :mcp/error (cond-> {:code code :message message}
                       extra (merge extra))
          :mcp/exit 1)))

(defn- invalid-args
  [ctx message]
  (fail ctx "invalid-args" message))

(defn- apply-help
  [ctx]
  (let [opts (:mcp/opts ctx)
        opts-text (cli/format-opts cli-opts)]
    (if-let [kind (briefing/plain-help-kind opts)]
      (assoc ctx
             :mcp/plain-text (briefing/plain-help-text kind opts-text)
             :mcp/exit 0)
      (when (:help opts)
        (assoc ctx
               :mcp/result (briefing/cli-help-text opts-text)
               :mcp/exit 0)))))

(defn- readme-job
  [opts]
  (let [n (+ (if (:readme opts) 1 0)
             (if (:hreadme opts) 1 0)
             (if (contains? opts :readme-tool) 1 0))]
    (cond
      (> n 1) :conflict
      (or (:readme opts) (:hreadme opts)) :readme
      (contains? opts :readme-tool) :readme-tool
      :else nil)))

(defn- verb-error
  [ctx]
  (let [verb (:mcp/verb ctx)
        job (readme-job (:mcp/opts ctx))]
    (cond
      (and job verb)
      "--readme / --hreadme / --readme-tool cannot be combined with a method verb. See bb-mcp.md."
      (= :conflict job)
      "--readme, --hreadme, and --readme-tool are mutually exclusive. See bb-mcp.md."
      (and (nil? job) (nil? verb))
      "Missing MCP method. See bb-mcp.md."
      (seq (:mcp/extra-args ctx))
      "Unexpected extra arguments. See bb-mcp.md."
      (and (nil? job) (not (verbs verb)))
      (str "Unknown method: " verb))))

(defn- missing-readme-tool-name?
  [opts job]
  (and (= :readme-tool job)
       (or (not (string? (:readme-tool opts)))
           (string/blank? (:readme-tool opts)))))

(defn- flag-error
  [ctx]
  (let [opts (:mcp/opts ctx)
        verb (:mcp/verb ctx)
        job (readme-job opts)]
    (cond
      (not (and (:server-name opts) (:window-id opts)))
      "Required: --server-name and --window-id (copy from `bb list`)."
      (missing-readme-tool-name? opts job)
      "--readme-tool needs a tool name."
      (and (= "tools/call" verb) (not (:name opts)))
      "tools/call requires --name."
      (and (= "resources/read" verb) (not (:uri opts)))
      "resources/read requires --uri."
      (and (contains? opts :args) (not= "-" (:args opts)))
      "Only `--args -` is supported (JSON on stdin).")))

(defn- validate-cli
  [ctx]
  (if-let [helped (apply-help ctx)]
    helped
    (if-let [msg (or (verb-error ctx) (flag-error ctx))]
      (invalid-args ctx msg)
      ctx)))

(defn parse-cli
  "Parses `:mcp/argv` into `:mcp/verb` and `:mcp/opts`. Unknown flags are `invalid-args`."
  [ctx]
  (try
    (let [{:keys [args opts]} (cli/parse-args (or (:mcp/argv ctx) []) cli-opts)]
      (validate-cli (assoc ctx
                           :mcp/opts opts
                           :mcp/verb (first args)
                           :mcp/extra-args (vec (rest args)))))
    (catch Exception e
      (if (= :restrict (:cause (ex-data e)))
        (invalid-args ctx (or (:msg (ex-data e)) (ex-message e)))
        (throw e)))))

(defn gather-stdin
  "Reads a JSON object from stdin when `tools/call` is given `--args -`. Otherwise arguments are `{}`."
  [ctx]
  (cond
    (:mcp/error ctx) ctx
    (not (and (= "tools/call" (:mcp/verb ctx))
              (= "-" (get-in ctx [:mcp/opts :args]))))
    (assoc ctx :mcp/arguments {})
    (some? (System/console))
    (invalid-args ctx "`--args -` needs JSON on stdin (not a TTY).")
    :else
    (let [raw (string/trim (slurp *in*))]
      (if (string/blank? raw)
        (invalid-args ctx "`--args -` needs a JSON object on stdin.")
        (try
          (let [parsed (json/parse-string raw true)]
            (if (map? parsed)
              (assoc ctx :mcp/arguments parsed)
              (invalid-args ctx "`--args -` needs a JSON object on stdin.")))
          (catch Exception _
            (invalid-args ctx "`--args -` needs a JSON object on stdin.")))))))

(defn- shard-match?
  [shard opts]
  (and (= (:serverName shard) (:server-name opts))
       (= (:windowId shard) (:window-id opts))))

(defn- mcp-ready?
  [shard]
  (and (get-in shard [:mcp :host])
       (get-in shard [:mcp :port])))

(defn resolve-window
  "Finds the live shard for `--server-name` and `--window-id`. Missing is `unknown-id`; stale or no MCP address is `window-gone`."
  [ctx]
  (if (:mcp/error ctx)
    ctx
    (let [dir (listing/windows-dir (:mcp/opts ctx))
          match (->> (listing/shard-paths dir)
                     (keep listing/read-shard)
                     (some (fn [shard]
                             (when (shard-match? shard (:mcp/opts ctx))
                               shard))))]
      (cond
        (nil? match)
        (fail ctx "unknown-id" "No shard for that --server-name and --window-id. Run `bb list` again.")
        (not (listing/live? match))
        (fail ctx "window-gone" "That window is gone. Run `bb list` again.")
        (not (mcp-ready? match))
        (fail ctx "window-gone" "That window is gone. Run `bb list` again.")
        :else (assoc ctx :mcp/dir dir :mcp/shard match)))))

(defn- rpc-params
  [verb opts arguments]
  (case verb
    "initialize" {:clientInfo {:name "bb-mcp"}}
    "tools/call" {:name (:name opts) :arguments (or arguments {})}
    "resources/read" {:uri (:uri opts)}
    {}))

(defn build-rpc
  "Builds the JSON-RPC request map as `:mcp/rpc-request`. Id is always `1`."
  [ctx]
  (if (:mcp/error ctx)
    ctx
    (assoc ctx :mcp/rpc-request {:jsonrpc "2.0"
                                 :id 1
                                 :method (:mcp/verb ctx)
                                 :params (rpc-params (:mcp/verb ctx)
                                                     (:mcp/opts ctx)
                                                     (:mcp/arguments ctx))})))

(defn skip-notification?
  "True for a JSON-RPC object that has `:method`, no `:id` key, and no `:error`."
  [msg]
  (boolean (and (map? msg)
                (:method msg)
                (not (contains? msg :id))
                (not (contains? msg :error)))))

(defn take-rpc-outcome
  "Classifies a parsed socket object: `:skip`, `{:kind :error :msg msg}`, or `{:kind :result :msg msg}`."
  [msg request-id]
  (cond
    (skip-notification? msg) :skip
    (and (map? msg) (contains? msg :error)) {:kind :error :msg msg}
    (and (map? msg) (= request-id (:id msg))) {:kind :result :msg msg}
    :else :skip))

(defn map-rpc-error
  "Maps a JSON-RPC error object onto a CLI token. Copies the RPC error onto `:rpc`."
  [ctx]
  (if-let [rpc-err (get-in ctx [:mcp/rpc-response :error])]
    (let [rpc-code (:code rpc-err)
          verb (:mcp/verb ctx)
          token (cond
                  (and (= -32601 rpc-code) (= "tools/call" verb)) "unknown-tool"
                  (and (= -32602 rpc-code) (= "resources/read" verb)) "unknown-resource"
                  (= -32603 rpc-code) "tool-error"
                  :else "protocol-error")]
      (fail ctx token (or (:message rpc-err) "JSON-RPC error") {:rpc rpc-err}))
    ctx))

(defn apply-rpc-result
  "Takes `:mcp/rpc-response` into `:mcp/result`, or maps a JSON-RPC error."
  [ctx]
  (cond
    (:mcp/error ctx) ctx
    (get-in ctx [:mcp/rpc-response :error]) (map-rpc-error ctx)
    :else (assoc ctx :mcp/result (get-in ctx [:mcp/rpc-response :result]))))

(defn- mime-extension
  [mime]
  (if-let [sub (some-> mime
                       (string/split #"/" 2)
                       second
                       (string/split #"[;+]")
                       first)]
    (if (re-matches #"[A-Za-z0-9.-]+" sub)
      (str "." sub)
      ".bin")
    ".bin"))

(defn- media-target?
  [part]
  (boolean
   (or (and (#{"image" "audio"} (:type part))
            (:data part))
       (contains? part :blob))))

(defn- part-bytes
  [part]
  (let [b64 (or (:data part) (:blob part))]
    (.decode (Base64/getDecoder) b64)))

(defn- rewrite-part-seq
  [parts media-dir]
  (reduce (fn [[out writes] part]
            (if (media-target? part)
              (let [path (str (fs/path media-dir (str (random-uuid) (mime-extension (:mimeType part)))))]
                [(conj out (-> part (dissoc :data :blob) (assoc :path path)))
                 (conj writes {:path path :bytes (part-bytes part)})])
              [(conj out part) writes]))
          [[] []]
          parts))

(defn- walk-media
  [v media-dir]
  (cond
    (map? v)
    (reduce-kv (fn [[m writes] k val]
                 (if (and (#{:content :contents} k)
                          (sequential? val))
                   (let [[parts more] (rewrite-part-seq val media-dir)]
                     [(assoc m k parts) (into writes more)])
                   (let [[v' more] (walk-media val media-dir)]
                     [(assoc m k v') (into writes more)])))
               [{} []]
               v)
    (sequential? v)
    (reduce (fn [[out writes] item]
              (let [[v' more] (walk-media item media-dir)]
                [(conj out v') (into writes more)]))
            [[] []]
            v)
    :else [v []]))

(defn plan-media-writes
  "Walks `result` `:content` / `:contents`. Image, audio, and `:blob` parts become path writes; text is left as-is."
  [result media-dir]
  (let [[result writes] (walk-media result media-dir)]
    {:result result :writes writes}))

(defn media-dir-for
  "Returns `mcp-media/<shard-name>` beside the registry home derived from `--dir`."
  [ctx]
  (let [windows-dir (or (:mcp/dir ctx)
                        (listing/windows-dir (:mcp/opts ctx)))
        registry-home (fs/parent windows-dir)
        stem (or (get-in ctx [:mcp/shard :name])
                 (str (get-in ctx [:mcp/shard :serverName])
                      "-"
                      (get-in ctx [:mcp/shard :windowId])))]
    (str (fs/path (fs/parent registry-home) "mcp-media" stem))))

(defn rewrite-media-parts
  "Writes planned media bytes under the window media dir and puts `:path` on those parts."
  [ctx]
  (if (or (:mcp/error ctx) (nil? (:mcp/result ctx)))
    ctx
    (let [dir (media-dir-for ctx)
          {:keys [result writes]} (plan-media-writes (:mcp/result ctx) dir)]
      (doseq [{:keys [path bytes]} writes]
        (fs/create-dirs (fs/parent path))
        (fs/write-bytes path bytes))
      (assoc ctx :mcp/result result))))

(defn- parse-rpc-line
  [raw]
  (try
    (json/parse-string raw true)
    (catch Exception _
      ::malformed)))

(defn- classify-line
  [raw]
  (let [msg (parse-rpc-line raw)]
    (if (= ::malformed msg)
      {:kind :malformed}
      (let [outcome (take-rpc-outcome msg 1)]
        (if (= :skip outcome)
          :skip
          {:kind :rpc :msg (:msg outcome)})))))

(defn- read-rpc-loop
  [in]
  (loop []
    (if-let [raw (.readLine in)]
      (let [classified (classify-line raw)]
        (if (= :skip classified)
          (recur)
          classified))
      {:kind :closed})))

(defn- apply-rpc-read
  [ctx {:keys [kind msg]}]
  (case kind
    :closed (fail ctx "protocol-error" "Socket closed before a response")
    :malformed (fail ctx "protocol-error" "Malformed JSON from the socket")
    :rpc (assoc ctx :mcp/rpc-response msg)))

(defn- apply-connect-ex
  [ctx e]
  (cond
    (instance? java.net.ConnectException e)
    (fail ctx "window-gone" (or (ex-message e) "Connection refused"))
    (instance? java.net.SocketTimeoutException e)
    (fail ctx "window-gone" "Connect timed out")
    (and (instance? java.net.SocketException e)
         (re-find #"(?i)refused" (or (ex-message e) "")))
    (fail ctx "window-gone" (ex-message e))
    :else
    (fail ctx "protocol-error" (or (ex-message e) "Connect failed"))))

(defn- apply-read-ex
  [ctx e]
  (if (instance? java.net.SocketTimeoutException e)
    (fail ctx "timeout" "Timed out waiting for a response")
    (fail ctx "protocol-error" (or (ex-message e) "Socket error"))))

(defn- set-request-timeout!
  [sock timeout-s]
  (when (pos? timeout-s)
    (.setSoTimeout sock (int (* timeout-s 1000)))))

(defn- write-rpc!
  [sock request]
  (let [out (.getOutputStream sock)
        payload (str (json/generate-string request) "\n")]
    (.write out (.getBytes payload "UTF-8"))
    (.flush out)))

(defn- socket-reader
  [sock]
  (BufferedReader. (InputStreamReader. (.getInputStream sock) "UTF-8")))

(defn- exchange-connected!
  [ctx sock]
  (let [timeout-s (get-in ctx [:mcp/opts :timeout] 180)]
    (set-request-timeout! sock timeout-s)
    (write-rpc! sock (:mcp/rpc-request ctx))
    (apply-rpc-read ctx (read-rpc-loop (socket-reader sock)))))

(defn- connect-socket!
  [sock host port]
  (.connect sock (InetSocketAddress. host (int port)) 5000))

(defn- connected-exchange
  [ctx sock]
  (let [host (get-in ctx [:mcp/shard :mcp :host])
        port (get-in ctx [:mcp/shard :mcp :port])
        ctx (try
              (connect-socket! sock host port)
              ctx
              (catch Exception e
                (apply-connect-ex ctx e)))]
    (if (:mcp/error ctx)
      ctx
      (try
        (exchange-connected! ctx sock)
        (catch Exception e
          (apply-read-ex ctx e))))))

(defn exchange-rpc!
  "Sends `:mcp/rpc-request` to the shard socket and stores `:mcp/rpc-response`.
   Connect failure in 5s is `window-gone`; request deadline is `--timeout`."
  [ctx]
  (if (:mcp/error ctx)
    ctx
    (let [sock (Socket.)]
      (try
        (connected-exchange ctx sock)
        (finally
          (try
            (.close sock)
            (catch Exception _)))))))

(defn envelope
  "Returns `{:ok true :result …}` or `{:ok false :error …}` from `ctx`."
  [ctx]
  (if-let [err (:mcp/error ctx)]
    {:ok false :error err}
    {:ok true :result (:mcp/result ctx)}))

(defn request-method!
  "Sends one JSON-RPC method on the resolved shard. Returns ctx with `:mcp/result` or `:mcp/error`."
  [ctx method params]
  (-> ctx
      (dissoc :mcp/error :mcp/exit :mcp/result :mcp/rpc-response)
      (assoc :mcp/verb method
             :mcp/rpc-request {:jsonrpc "2.0"
                               :id 1
                               :method method
                               :params params})
      exchange-rpc!
      apply-rpc-result))

(defn compose-readme-briefing
  [init-result resources-result tools-result]
  (briefing/compose-readme-briefing init-result resources-result tools-result))

(defn format-readme-text
  [briefing]
  (briefing/format-readme-text briefing))

(defn run-readme!
  "Runs initialize, resources/list, and tools/list (with userDescription) and composes one briefing."
  [ctx]
  (briefing/maybe-plain-readme (briefing/run-readme! ctx request-method!)))

(defn pick-readme-tool
  "Returns the `--readme-tool` result map, or nil when the name is missing."
  [tools-result tool-name]
  (briefing/pick-readme-tool tools-result tool-name))

(defn run-readme-tool!
  "Lists tools and returns one tool's name, description, and inputSchema."
  [ctx]
  (briefing/run-readme-tool! ctx request-method! fail))

(defn run-pipeline
  "Runs parse through media rewrite. Bind a ctx map and replay from any step."
  [ctx]
  (let [parsed (parse-cli ctx)]
    (cond
      (:mcp/error parsed) parsed
      (:mcp/plain-text parsed) parsed
      (get-in parsed [:mcp/opts :help]) parsed
      :else
      (let [prepared (-> parsed gather-stdin resolve-window)]
        (if (:mcp/error prepared)
          prepared
          (case (readme-job (:mcp/opts prepared))
            :readme (run-readme! prepared)
            :readme-tool (run-readme-tool! prepared)
            (-> prepared
                build-rpc
                exchange-rpc!
                apply-rpc-result
                rewrite-media-parts)))))))

(defn main!
  "Prints one JSON envelope on stdout and returns 0 or 1, except `--hhelp` / `--hreadme-tool` (help text) and `--hreadme` (briefing as text), which print plain text and return 0. `--help` / `-h` print usage as a JSON success envelope. Require-safe: no `System/exit`."
  [args]
  (try
    (let [ctx (run-pipeline {:mcp/argv args})]
      (if-let [text (:mcp/plain-text ctx)]
        (do (println text)
            (or (:mcp/exit ctx) 0))
        (do (println (json/generate-string (envelope ctx)))
            (or (:mcp/exit ctx) 0))))
    (catch Exception e
      (println (json/generate-string {:ok false
                                      :error {:code "protocol-error"
                                              :message (ex-message e)}}))
      1)))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (main! *command-line-args*)))
