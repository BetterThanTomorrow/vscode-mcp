(ns mcp-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as string]
   [clojure.test :refer [deftest is]]
   [mcp :as mcp]))

(def png-b64
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")

(defn- current-pid
  []
  (.pid (java.lang.ProcessHandle/current)))

(defn- iso-now
  []
  (str (java.time.Instant/now)))

(defn- iso-old
  []
  (str (.minusSeconds (java.time.Instant/now) 120)))

(defn- live-shard
  [& {:keys [server-name window-id host port omit-mcp?]
      :or {server-name "bd" window-id "ws-1" host "127.0.0.1" port 9}}]
  (cond-> {:schemaVersion 1
           :name (str server-name "-" window-id)
           :serverName server-name
           :windowId window-id
           :pid (current-pid)
           :updatedAt (iso-now)}
    (not omit-mcp?) (assoc :mcp {:host host :port port})))

(defn- write-shard!
  [dir shard]
  (fs/create-dirs dir)
  (spit (str (fs/path dir (str (:name shard) ".json")))
        (json/generate-string shard)))

(defn- with-tmp-dir
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (f tmp)
      (finally
        (fs/delete-tree tmp)))))

(defn- parse
  [& args]
  (mcp/parse-cli {:mcp/argv (vec args)}))

(defn- err-code
  [ctx]
  (get-in ctx [:mcp/error :code]))

(defn- capture-main
  [args]
  (let [exit (atom nil)
        out (with-out-str (reset! exit (mcp/main! args)))]
    {:exit @exit
     :parsed (json/parse-string out true)}))

(deftest parse-cli-help
  (let [help (parse "--help")
        dash-h (parse "-h")]
    (is (= "invalid-args" (err-code help)))
    (is (= 1 (:mcp/exit help)))
    (is (re-find #"bb-mcp.md" (get-in help [:mcp/error :message])))
    (is (re-find #"--server-name" (get-in help [:mcp/error :message])))
    (is (= "invalid-args" (err-code dash-h)))))

(deftest parse-cli-restrict
  (let [unknown (parse "ping" "--nope" "x")]
    (is (= "invalid-args" (err-code unknown)))
    (is (re-find #"Unknown option" (get-in unknown [:mcp/error :message])))))

(deftest parse-cli-missing-or-unknown
  (is (= "invalid-args" (err-code (parse))))
  (is (= "invalid-args" (err-code (parse "nope" "--server-name" "bd" "--window-id" "ws-1"))))
  (is (= "invalid-args" (err-code (parse "ping" "extra" "--server-name" "bd" "--window-id" "ws-1"))))
  (is (= "invalid-args" (err-code (parse "ping"))))
  (is (= "invalid-args" (err-code (parse "tools/call" "--server-name" "bd" "--window-id" "ws-1")))))

(deftest parse-cli-required-flags
  (is (= "invalid-args" (err-code (parse "resources/read" "--server-name" "bd" "--window-id" "ws-1"))))
  (is (= "invalid-args" (err-code (parse "tools/call" "--server-name" "bd" "--window-id" "ws-1" "--name" "t" "--args" "{}"))))
  (let [ok (parse "ping" "--server-name" "bd" "--window-id" "ws-1")]
    (is (nil? (:mcp/error ok)))
    (is (= "ping" (:mcp/verb ok)))
    (is (= 180 (get-in ok [:mcp/opts :timeout]))))
  (is (= 0 (get-in (parse "ping" "--server-name" "bd" "--window-id" "ws-1" "--timeout" "0")
                   [:mcp/opts :timeout]))))

(deftest gather-stdin-ok
  (is (= {} (:mcp/arguments (mcp/gather-stdin {:mcp/verb "ping" :mcp/opts {}}))))
  (is (= {} (:mcp/arguments (mcp/gather-stdin {:mcp/verb "tools/call" :mcp/opts {:name "t"}}))))
  (is (= {:x 1}
         (:mcp/arguments (with-in-str "{\"x\":1}"
                           (mcp/gather-stdin {:mcp/verb "tools/call" :mcp/opts {:args "-"}}))))))

(deftest gather-stdin-invalid
  (is (= "invalid-args"
         (err-code (with-in-str ""
                     (mcp/gather-stdin {:mcp/verb "tools/call" :mcp/opts {:args "-"}})))))
  (is (= "invalid-args"
         (err-code (with-in-str "not-json"
                     (mcp/gather-stdin {:mcp/verb "tools/call" :mcp/opts {:args "-"}})))))
  (is (= "invalid-args"
         (err-code (with-in-str "[1]"
                     (mcp/gather-stdin {:mcp/verb "tools/call" :mcp/opts {:args "-"}}))))))

(deftest resolve-window-live-stale-missing
  (with-tmp-dir
    (fn [tmp]
      (let [windows (str (fs/path tmp "registry" "windows"))
            opts {:server-name "bd" :window-id "ws-1" :dir windows}]
        (write-shard! windows (live-shard))
        (let [ok (mcp/resolve-window {:mcp/opts opts})]
          (is (nil? (:mcp/error ok)))
          (is (= "bd" (get-in ok [:mcp/shard :serverName])))
          (is (= windows (:mcp/dir ok))))
        (is (= "unknown-id"
               (err-code (mcp/resolve-window {:mcp/opts (assoc opts :window-id "nope")}))))
        (write-shard! windows (assoc (live-shard) :updatedAt (iso-old)))
        (is (= "window-gone" (err-code (mcp/resolve-window {:mcp/opts opts}))))
        (write-shard! windows (live-shard :omit-mcp? true))
        (is (= "window-gone" (err-code (mcp/resolve-window {:mcp/opts opts}))))))))

(deftest take-rpc-outcome-skip
  (is (= :skip (mcp/take-rpc-outcome {:method "notifications/progress"} 1)))
  (is (= :skip (mcp/take-rpc-outcome {:jsonrpc "2.0" :id 2 :result {}} 1)))
  (is (mcp/skip-notification? {:method "notifications/progress"}))
  (is (not (mcp/skip-notification? {:method "x" :error {:code 1}}))))

(deftest take-rpc-outcome-keep
  (is (= :error (:kind (mcp/take-rpc-outcome {:id nil :error {:code -32700 :message "Parse error"}} 1))))
  (is (= :error (:kind (mcp/take-rpc-outcome {:error {:code -32700 :message "Parse error"}} 1))))
  (is (= :result (:kind (mcp/take-rpc-outcome {:id 1 :result {}} 1)))))

(deftest map-rpc-error-tokens
  (is (= "unknown-tool"
         (err-code (mcp/map-rpc-error {:mcp/verb "tools/call"
                                       :mcp/rpc-response {:error {:code -32601 :message "Unknown tool"}}}))))
  (is (= "unknown-resource"
         (err-code (mcp/map-rpc-error {:mcp/verb "resources/read"
                                       :mcp/rpc-response {:error {:code -32602 :message "Resource not found"}}}))))
  (is (= "tool-error"
         (err-code (mcp/map-rpc-error {:mcp/verb "tools/call"
                                       :mcp/rpc-response {:error {:code -32603 :message "boom"}}}))))
  (is (= "protocol-error"
         (err-code (mcp/map-rpc-error {:mcp/verb "tools/call"
                                       :mcp/rpc-response {:error {:code -32700 :message "Parse error"}}}))))
  (is (= "protocol-error"
         (err-code (mcp/map-rpc-error {:mcp/verb "ping"
                                       :mcp/rpc-response {:error {:code -32601 :message "Unknown tool"}}}))))
  (let [rpc {:code -32601 :message "Unknown tool"}
        ctx (mcp/map-rpc-error {:mcp/verb "tools/call"
                                :mcp/rpc-response {:error rpc}})]
    (is (= rpc (get-in ctx [:mcp/error :rpc])))))

(deftest build-rpc-shapes
  (is (= {:clientInfo {:name "bb-mcp"}}
         (get-in (mcp/build-rpc {:mcp/verb "initialize" :mcp/opts {}})
                 [:mcp/rpc-request :params])))
  (is (= {:name "t" :arguments {:x 1}}
         (get-in (mcp/build-rpc {:mcp/verb "tools/call"
                                 :mcp/opts {:name "t"}
                                 :mcp/arguments {:x 1}})
                 [:mcp/rpc-request :params])))
  (is (= {:uri "skill://x"}
         (get-in (mcp/build-rpc {:mcp/verb "resources/read" :mcp/opts {:uri "skill://x"}})
                 [:mcp/rpc-request :params])))
  (is (= {}
         (get-in (mcp/build-rpc {:mcp/verb "ping" :mcp/opts {}})
                 [:mcp/rpc-request :params])))
  (is (= 1 (get-in (mcp/build-rpc {:mcp/verb "ping" :mcp/opts {}})
                   [:mcp/rpc-request :id]))))

(deftest plan-media-writes-leaves-text
  (let [planned (mcp/plan-media-writes
                 {:content [{:type "text" :text "data:image/png;base64,NO"}
                            {:type "image" :mimeType "image/png" :data png-b64}]}
                 "/tmp/mcp-media-test")
        content (:content (:result planned))]
    (is (= 1 (count (:writes planned))))
    (is (= "data:image/png;base64,NO" (:text (first content))))
    (is (not (contains? (nth content 1) :data)))
    (is (string/ends-with? (:path (nth content 1)) ".png"))))

(deftest plan-media-writes-audio-and-blob
  (let [planned (mcp/plan-media-writes
                 {:content [{:type "audio" :mimeType "audio/wav" :data png-b64}]
                  :contents [{:uri "x" :mimeType "image/png" :blob png-b64}]}
                 "/tmp/mcp-media-test")
        content (:content (:result planned))
        contents (:contents (:result planned))]
    (is (= 2 (count (:writes planned))))
    (is (string/ends-with? (:path (first content)) ".wav"))
    (is (not (contains? (first contents) :blob)))))

(deftest rewrite-media-parts-writes-files
  (with-tmp-dir
    (fn [tmp]
      (let [windows (str (fs/path tmp "registry" "windows"))
            ctx {:mcp/dir windows
                 :mcp/shard {:name "bd-ws-1"}
                 :mcp/result {:content [{:type "image" :mimeType "image/png" :data png-b64}]}}
            out (mcp/rewrite-media-parts ctx)
            part (first (get-in out [:mcp/result :content]))]
        (is (fs/exists? (:path part)))
        (is (not (contains? part :data)))
        (is (= "image" (:type part)))
        (is (string/includes? (:path part) "mcp-media/bd-ws-1"))))))

(deftest envelope-shapes
  (is (= {:ok true :result {:a 1}} (mcp/envelope {:mcp/result {:a 1}})))
  (is (= {:ok false :error {:code "timeout" :message "x"}}
         (mcp/envelope {:mcp/error {:code "timeout" :message "x"}}))))

(deftest main-help-and-uncaught
  (let [{:keys [exit parsed]} (capture-main ["--help"])]
    (is (= 1 exit))
    (is (false? (:ok parsed)))
    (is (= "invalid-args" (get-in parsed [:error :code])))
    (is (re-find #"bb-mcp.md" (get-in parsed [:error :message]))))
  (with-redefs [mcp/parse-cli (fn [_]
                                (throw (ex-info "boom" {})))]
    (let [{:keys [exit parsed]} (capture-main ["ping"])]
      (is (= 1 exit))
      (is (false? (:ok parsed)))
      (is (= "protocol-error" (get-in parsed [:error :code])))
      (is (= "boom" (get-in parsed [:error :message]))))))
