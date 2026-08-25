(ns vscode-mcp.registry-writer-test
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [cljs.test :refer [async deftest is]]
   [promesa.core :as p]
   [vscode-mcp.registry-writer :as sut]))

(defn- tmp-dir []
  (fs/mkdtempSync (path/join (os/tmpdir) "vscode-mcp-reg-writer-")))

(defn- tmp-config
  [dir extra]
  (merge {:registry/enabled? true
          :registry/dir dir
          :registry/debounce-ms 15
          :registry/heartbeat-ms 10000
          :cursor/server-name "backseat-driver"
          :cursor/script-relative-path "dist/foo.js"
          :lifecycle/wrapper-install-dir dir
          :server/host "127.0.0.1"}
         extra))

(defn- tmp-info []
  {:server/instance-slug "ws-abc"
   :server/assigned-port 1664
   :server/host "127.0.0.1"
   :server/workspace-root "/proj"
   :server/port-file-uri #js {:fsPath "/tmp/port"}})

(defn- shard-path [dir]
  (path/join dir "backseat-driver-ws-abc.json"))

(defn- read-shard [dir]
  (let [p (shard-path dir)]
    (when (fs/existsSync p)
      (js->clj (js/JSON.parse (fs/readFileSync p "utf8"))
               :keywordize-keys true))))

(defn- delay+ [ms]
  (p/create (fn [resolve]
              (js/setTimeout resolve ms))))

(defn- cleanup! [dir]
  (sut/clear-writers!)
  (when (and dir (fs/existsSync dir))
    (fs/rmSync dir #js {:recursive true :force true})))

(deftest on-started-writes-shard-test
  (async done
         (let [dir (tmp-dir)
               calls (atom 0)
               config (tmp-config dir {:registry/custom-data+
                                       (fn [_]
                                         (swap! calls inc)
                                         (p/resolved {:sessions [{:replSessionKey "clj"}]}))})]
           (-> (sut/on-started!+ config (tmp-info))
               (p/then (fn [_]
                         (let [doc (read-shard dir)]
                           (is (= 1 @calls))
                           (is (= 1 (:schemaVersion doc)))
                           (is (= "backseat-driver-ws-abc" (:name doc)))
                           (is (= "backseat-driver" (:serverName doc)))
                           (is (= "ws-abc" (:windowId doc)))
                           (is (= "/proj" (:workspaceRoot doc)))
                           (is (= (.-pid js/process) (:pid doc)))
                           (is (= 1664 (get-in doc [:mcp :port])))
                           (is (= [{:replSessionKey "clj"}] (:sessions doc))))))
               (p/finally (fn []
                            (cleanup! dir)
                            (done)))))))

(deftest disabled-config-is-noop-test
  (async done
         (let [dir (tmp-dir)
               config (tmp-config dir {:registry/enabled? false})]
           (-> (sut/on-started!+ config (tmp-info))
               (p/then (fn [_]
                         (sut/update-registry!+ config)
                         (is (not (fs/existsSync (shard-path dir))))))
               (p/finally (fn []
                            (cleanup! dir)
                            (done)))))))

(deftest debounce-coalesces-refreshes-test
  (async done
         (let [dir (tmp-dir)
               !sessions (atom "a")
               calls (atom 0)
               config (tmp-config
                       dir
                       {:registry/custom-data+
                        (fn [_]
                          (swap! calls inc)
                          (p/resolved {:sessions [@!sessions]}))})]
           (-> (sut/on-started!+ config (tmp-info))
               (p/then (fn [_]
                         (reset! !sessions "b")
                         (sut/update-registry!+ config)
                         (reset! !sessions "c")
                         (sut/update-registry!+ config)
                         (delay+ 50)))
               (p/then (fn [_]
                         (let [doc (read-shard dir)]
                           (is (= ["c"] (:sessions doc)))
                           (is (= 2 @calls)
                               "start write plus one debounced refresh"))))
               (p/finally (fn []
                            (cleanup! dir)
                            (done)))))))

(deftest stop-fences-in-flight-custom-data-test
  (async done
         (let [dir (tmp-dir)
               config (tmp-config
                       dir
                       {:registry/custom-data+
                        (fn [_]
                          (p/then (delay+ 80)
                                  (constantly {:sessions [{:stale true}]})))})
               info (tmp-info)]
           (sut/on-started!+ config info)
           (sut/on-stopping! config)
           (sut/on-stopped! config info)
           (-> (delay+ 120)
               (p/then (fn [_]
                         (is (not (fs/existsSync (shard-path dir)))
                             "stale custom-data must not recreate the shard")))
               (p/finally (fn []
                            (cleanup! dir)
                            (done)))))))

(deftest heartbeat-bumps-updated-at-without-custom-data-test
  (async done
         (let [dir (tmp-dir)
               calls (atom 0)
               config (tmp-config
                       dir
                       {:registry/heartbeat-ms 40
                        :registry/custom-data+
                        (fn [_]
                          (swap! calls inc)
                          (p/resolved {:sessions []}))})]
           (-> (sut/on-started!+ config (tmp-info))
               (p/then (fn [_]
                         (let [first-at (:updatedAt (read-shard dir))]
                           (-> (delay+ 90)
                               (p/then (fn [_]
                                         (let [doc (read-shard dir)]
                                           (is (= 1 @calls))
                                           (is (some? first-at))
                                           (is (not= first-at (:updatedAt doc)))
                                           (is (= [] (:sessions doc))))))))))
               (p/finally (fn []
                            (cleanup! dir)
                            (done)))))))
