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

(defn- live-entry
  [& {:keys [server-name window-id host port omit-mcp?]
      :or {server-name "bd" window-id "ws-1" host "127.0.0.1" port 9}}]
  (cond-> {:schemaVersion 1
           :name (str server-name "-" window-id)
           :serverName server-name
           :windowId window-id
           :pid (current-pid)
           :updatedAt (iso-now)}
    (not omit-mcp?) (assoc :mcp {:host host :port port})))

(defn- write-entry!
  [dir entry]
  (fs/create-dirs dir)
  (spit (str (fs/path dir (str (:name entry) ".json")))
        (json/generate-string entry)))

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
    (is (nil? (:mcp/error help)))
    (is (= 0 (:mcp/exit help)))
    (is (re-find #"bb-mcp.md" (:mcp/result help)))
    (is (re-find #"--server-name" (:mcp/result help)))
    (is (nil? (:mcp/error dash-h)))
    (is (= 0 (:mcp/exit dash-h)))))

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
        (write-entry! windows (live-entry))
        (let [ok (mcp/resolve-window {:mcp/opts opts})]
          (is (nil? (:mcp/error ok)))
          (is (= "bd" (get-in ok [:mcp/entry :serverName])))
          (is (= windows (:mcp/dir ok))))
        (is (= "unknown-id"
               (err-code (mcp/resolve-window {:mcp/opts (assoc opts :window-id "nope")}))))
        (write-entry! windows (assoc (live-entry) :updatedAt (iso-old)))
        (is (= "window-gone" (err-code (mcp/resolve-window {:mcp/opts opts}))))
        (write-entry! windows (live-entry :omit-mcp? true))
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
                 :mcp/entry {:name "bd-ws-1"}
                 :mcp/result {:content [{:type "image" :mimeType "image/png" :data png-b64}]}}
            out (mcp/rewrite-media-parts ctx)
            part (first (get-in out [:mcp/result :content]))]
        (is (fs/exists? (:path part)))
        (is (not (contains? part :data)))
        (is (= "image" (:type part)))
        (is (fs/starts-with? (:path part) (fs/path tmp "mcp-media" "bd-ws-1")))))))

(deftest envelope-shapes
  (is (= {:ok true :result {:a 1}} (mcp/envelope {:mcp/result {:a 1}})))
  (is (= {:ok false :error {:code "timeout" :message "x"}}
         (mcp/envelope {:mcp/error {:code "timeout" :message "x"}}))))

(deftest main-help-and-uncaught
  (let [{:keys [exit parsed]} (capture-main ["--help"])]
    (is (= 0 exit))
    (is (true? (:ok parsed)))
    (is (string? (:result parsed)))
    (is (re-find #"bb-mcp.md" (:result parsed))))
  (with-redefs [mcp/parse-cli (fn [_]
                                (throw (ex-info "boom" {})))]
    (let [{:keys [exit parsed]} (capture-main ["ping"])]
      (is (= 1 exit))
      (is (false? (:ok parsed)))
      (is (= "protocol-error" (get-in parsed [:error :code])))
      (is (= "boom" (get-in parsed [:error :message]))))))

(deftest parse-cli-readme-flags
  (let [ok (parse "--readme" "--server-name" "bd" "--window-id" "ws-1")]
    (is (nil? (:mcp/error ok)))
    (is (nil? (:mcp/verb ok)))
    (is (true? (get-in ok [:mcp/opts :readme]))))
  (let [tool (parse "--readme-tool" "clojure_evaluate_code" "--server-name" "bd" "--window-id" "ws-1")]
    (is (nil? (:mcp/error tool)))
    (is (= "clojure_evaluate_code" (get-in tool [:mcp/opts :readme-tool]))))
  (let [hreadme (parse "--hreadme" "--server-name" "bd" "--window-id" "ws-1")]
    (is (nil? (:mcp/error hreadme)))
    (is (true? (get-in hreadme [:mcp/opts :hreadme]))))
  (is (= "invalid-args" (err-code (parse "--readme"))))
  (is (= "invalid-args" (err-code (parse "--hreadme"))))
  (is (= "invalid-args" (err-code (parse "--readme-tool" "t"))))
  (is (= "invalid-args" (err-code (parse "--readme" "ping" "--server-name" "bd" "--window-id" "ws-1"))))
  (is (= "invalid-args" (err-code (parse "ping" "--readme" "--server-name" "bd" "--window-id" "ws-1"))))
  (is (= "invalid-args" (err-code (parse "--readme" "--hreadme" "--server-name" "bd" "--window-id" "ws-1"))))
  (is (= "invalid-args" (err-code (parse "--readme" "--readme-tool" "t" "--server-name" "bd" "--window-id" "ws-1"))))
  (is (= "invalid-args" (err-code (parse "--readme-tool" "--server-name" "bd" "--window-id" "ws-1"))))
  (let [init (parse "initialize" "--server-name" "bd" "--window-id" "ws-1")]
    (is (nil? (:mcp/error init)))
    (is (= "initialize" (:mcp/verb init)))))

(deftest parse-cli-help-readme-recipe
  (let [msg (:mcp/result (parse "--help"))]
    (is (re-find #"--readme" msg))
    (is (re-find #"--readme-tool" msg))
    (is (not (re-find #"initialize" msg)))))

(deftest parse-cli-hhelp
  (let [hhelp (parse "--hhelp")
        hreadme-tool (parse "--hreadme-tool")]
    (is (nil? (:mcp/error hhelp)))
    (is (= 0 (:mcp/exit hhelp)))
    (is (re-find #"--readme" (:mcp/plain-text hhelp)))
    (is (re-find #"bb-mcp.md" (:mcp/plain-text hhelp)))
    (is (nil? (:mcp/error hreadme-tool)))
    (is (= 0 (:mcp/exit hreadme-tool)))
    (is (or (re-find #"inputSchema" (:mcp/plain-text hreadme-tool))
            (re-find #"tools/call" (:mcp/plain-text hreadme-tool))))))

(deftest main-hhelp-plain-text
  (let [hhelp-exit (atom nil)
        hhelp-out (with-out-str (reset! hhelp-exit (mcp/main! ["--hhelp"])))]
    (is (= 0 @hhelp-exit))
    (is (not (string/starts-with? (string/trim hhelp-out) "{")))
    (is (re-find #"--readme" hhelp-out))
    (is (or (re-find #"--hhelp" hhelp-out) (re-find #"bb-mcp.md" hhelp-out))))
  (let [hrt-exit (atom nil)
        hrt-out (with-out-str (reset! hrt-exit (mcp/main! ["--hreadme-tool"])))]
    (is (= 0 @hrt-exit))
    (is (not (string/starts-with? (string/trim hrt-out) "{")))
    (is (or (re-find #"inputSchema" hrt-out) (re-find #"tools/call" hrt-out)))))

(deftest format-readme-text-shape
  (let [briefing {:serverInfo {:name "bd" :version "1"}
                  :description "A server"
                  :instructions "Use the tools."
                  :skills [{:name "sk"
                            :uri "skill://sk/SKILL.md"
                            :description "A skill"}]
                  :tools [{:name "t"
                           :userDescription "User"}]
                  :next "Read relevant skills with `resources/read`."}
        text (mcp/format-readme-text briefing)]
    (is (re-find #"bd" text))
    (is (re-find #"A server" text))
    (is (re-find #"Use the tools." text))
    (is (re-find #"skill://sk/SKILL.md" text))
    (is (re-find #"User" text))
    (is (re-find #"resources/read" text))
    (is (not (string/starts-with? (string/trim text) "{")))))

(deftest compose-readme-briefing-shape
  (let [briefing (mcp/compose-readme-briefing
                  {:serverInfo {:name "bd" :version "1"}
                   :instructions "Use the tools."
                   :description "A server"}
                  {:resources [{:name "sk"
                                :uri "skill://sk/SKILL.md"
                                :description "A skill"
                                :mimeType "text/markdown"
                                :text "BODY"}]}
                  {:tools [{:name "t"
                            :description "Model"
                            :userDescription "User"
                            :inputSchema {:type "object"}}]})]
    (is (= {:name "bd" :version "1"} (:serverInfo briefing)))
    (is (= "Use the tools." (:instructions briefing)))
    (is (= "A server" (:description briefing)))
    (is (= [{:name "sk" :uri "skill://sk/SKILL.md" :description "A skill"}]
           (:skills briefing)))
    (is (= [{:name "t" :userDescription "User"}] (:tools briefing)))
    (is (re-find #"resources/read" (:next briefing)))
    (is (re-find #"--readme-tool" (:next briefing)))
    (is (not (re-find #"BODY" (pr-str briefing))))
    (is (not (contains? (first (:tools briefing)) :inputSchema)))))

(deftest pick-readme-tool-known-and-unknown
  (let [listed {:tools [{:name "t"
                         :description "Model"
                         :inputSchema {:type "object"}
                         :userDescription "User"}]}
        picked (mcp/pick-readme-tool listed "t")]
    (is (= "t" (:name picked)))
    (is (= "Model" (:description picked)))
    (is (= {:type "object"} (:inputSchema picked)))
    (is (re-find #"tools/call" (:next picked)))
    (is (not (contains? picked :userDescription)))
    (is (nil? (mcp/pick-readme-tool listed "nope")))))

(deftest run-readme-tool-unknown-without-tcp
  (with-redefs [mcp/request-method! (fn [ctx _method _params]
                                      (assoc ctx :mcp/result {:tools [{:name "t"}]}))]
    (is (= "unknown-tool"
           (err-code (mcp/run-readme-tool! {:mcp/opts {:readme-tool "nope"}}))))))

(deftest hreadme-plain-text-from-briefing
  (let [init-result {:serverInfo {:name "bd"}
                     :instructions "i"
                     :description "d"}
        resources-result {:resources [{:name "sk"
                                       :uri "skill://sk/SKILL.md"
                                       :description "A skill"}]}
        tools-result {:tools [{:name "t"
                               :userDescription "User"
                               :description "Model"
                               :inputSchema {:type "object"}}]}]
    (with-redefs [mcp/request-method!
                  (fn [ctx method _params]
                    (assoc ctx :mcp/result
                           (case method
                             "initialize" init-result
                             "resources/list" resources-result
                             "tools/list" tools-result)))]
      (let [out (mcp/run-readme! {:mcp/opts {:hreadme true}})]
        (is (nil? (:mcp/error out)))
        (is (map? (:mcp/result out)))
        (let [text (:mcp/plain-text out)]
          (is (re-find #"bd" text))
          (is (re-find #"skill://sk/SKILL.md" text))
          (is (re-find #"User" text)))))))

(deftest run-readme-rpc-sequence
  (let [calls (atom [])
        init-result {:serverInfo {:name "bd"}
                     :instructions "i"
                     :description "d"}
        resources-result {:resources [{:name "sk"
                                       :uri "skill://sk/SKILL.md"
                                       :description "A skill"}]}
        tools-result {:tools [{:name "t"
                               :userDescription "User"
                               :description "Model"
                               :inputSchema {:type "object"}}]}]
    (with-redefs [mcp/request-method!
                  (fn [ctx method params]
                    (swap! calls conj {:method method :params params})
                    (assoc ctx :mcp/result
                           (case method
                             "initialize" init-result
                             "resources/list" resources-result
                             "tools/list" tools-result)))]
      (let [out (mcp/run-readme! {:mcp/opts {}})]
        (is (= [{:method "initialize" :params {:clientInfo {:name "bb-mcp"}}}
                {:method "resources/list" :params {}}
                {:method "tools/list" :params {:includeUserDescription true}}]
               @calls))
        (is (= [{:name "t" :userDescription "User"}]
               (:tools (:mcp/result out))))
        (is (nil? (:mcp/error out)))))))
