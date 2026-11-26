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
          :server/name "backseat-driver"
          :wrapper/script-relative-path "dist/foo.js"
          :lifecycle/wrapper-install-dir dir
          :server/host "127.0.0.1"}
         extra))

(defn- tmp-info []
  {:server/instance-slug "ws-abc"
   :server/assigned-port 1664
   :server/host "127.0.0.1"
   :server/app-id "cursor"
   :server/workspace-root "/proj"
   :server/workspace-folder "/proj"
   :server/port-file-uri #js {:fsPath "/tmp/port"}})

(defn- entry-path [dir]
  (path/join dir "backseat-driver-ws-abc.json"))

(defn- read-entry [dir]
  (let [p (entry-path dir)]
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

(deftest on-started-writes-entry-test
  (async done
         (let [dir (tmp-dir)
               config (tmp-config dir {:registry/custom-data {:sessions [{:replSessionKey "clj"}]}})]
           (-> (sut/on-started!+ config (tmp-info))
               (p/then (fn [_]
                         (let [doc (read-entry dir)]
                           (is (= [{:replSessionKey "clj"}] (:sessions doc))
                               "The entry of a server that starts has the custom data in the config")
                           (is (= 1 (:schemaVersion doc)))
                           (is (= "backseat-driver-ws-abc" (:name doc)))
                           (is (= "backseat-driver" (:serverName doc)))
                           (is (= "ws-abc" (:windowId doc)))
                           (is (= "cursor" (:appId doc)))
                           (is (= "/proj" (:workspaceRoot doc)))
                           (is (= "/proj" (:workspaceFolder doc))))))
               (p/finally (fn []
                            (cleanup! dir)
                            (done)))))))

(deftest custom-data-cannot-overwrite-the-entrys-own-fields-test
  (async done
         (let [dir (tmp-dir)
               config (tmp-config dir {:registry/custom-data {:serverName "impostor" :pid 1 :extra "kept"}})]
           (-> (sut/on-started!+ config (tmp-info))
               (p/then (fn [_]
                         (let [doc (read-entry dir)]
                           (is (= "backseat-driver" (:serverName doc)))
                           (is (not= 1 (:pid doc)))
                           (is (= "kept" (:extra doc))))))
               (p/finally (fn []
                            (cleanup! dir)
                            (done)))))))

(deftest debounce-coalesces-refreshes-test
  (async done
         (let [dir (tmp-dir)
               config (fn [sessions]
                        (tmp-config dir {:registry/custom-data {:sessions sessions}}))]
           (-> (sut/on-started!+ (config ["a"]) (tmp-info))
               (p/then (fn [_]
                         (is (= ["a"] (:sessions (read-entry dir))))
                         (sut/update-registry!+ (config ["b"]))
                         (sut/update-registry!+ (config ["c"]))
                         (is (= ["a"] (:sessions (read-entry dir)))
                             "A change is written after the debounce time, and not at once")
                         (delay+ 50)))
               (p/then (fn [_]
                         (is (= ["c"] (:sessions (read-entry dir)))
                             "Two changes inside the debounce time give one write, with the later data")))
               (p/finally (fn []
                            (cleanup! dir)
                            (done)))))))

(deftest a-write-that-waits-does-nothing-after-the-stop-test
  (async done
         (let [dir (tmp-dir)
               config (tmp-config dir {:registry/custom-data {:sessions ["a"]}})
               info (tmp-info)]
           (-> (sut/on-started!+ config info)
               (p/then (fn [_]
                         (sut/update-registry!+ (assoc config :registry/custom-data {:sessions ["late"]}))
                         (sut/on-stopping! config)
                         (sut/on-stopped! config info)
                         (delay+ 50)))
               (p/then (fn [_]
                         (is (not (fs/existsSync (entry-path dir)))
                             "A change that waited to be written when the server stopped doesn't bring the entry back")))
               (p/finally (fn []
                            (cleanup! dir)
                            (done)))))))

(deftest heartbeat-bumps-updated-at-test
  (async done
         (let [dir (tmp-dir)
               config (tmp-config dir {:registry/heartbeat-ms 40
                                       :registry/custom-data {:sessions []}})
               info (tmp-info)]
           (-> (sut/on-started!+ config info)
               (p/then (fn [_]
                         (let [first-at (:updatedAt (read-entry dir))]
                           (-> (delay+ 90)
                               (p/then (fn [_]
                                         (let [doc (read-entry dir)]
                                           (is (some? first-at))
                                           (is (not= first-at (:updatedAt doc))
                                               "The heartbeat writes the entry with a new time")
                                           (is (= [] (:sessions doc))
                                               "and the same data")
                                           (sut/on-stopping! config)
                                           (sut/on-stopped! config info)
                                           (delay+ 90))))
                               (p/then (fn [_]
                                         (is (not (fs/existsSync (entry-path dir)))
                                             "A heartbeat after the stop writes nothing")))))))
               (p/finally (fn []
                            (cleanup! dir)
                            (done)))))))

(deftest stop-then-start-writes-the-new-servers-entry-test
  (async done
         (let [dir (tmp-dir)
               info (tmp-info)
               first-config (tmp-config dir {:registry/custom-data {:sessions [{:first true}]}})
               second-config (tmp-config dir {:registry/custom-data {:sessions [{:second true}]}})]
           (-> (sut/on-started!+ first-config info)
               (p/then (fn [_]
                         (sut/update-registry!+ (assoc first-config :registry/custom-data {:sessions [{:late true}]}))
                         (sut/on-stopping! first-config)
                         (sut/on-stopped! first-config info)
                         (sut/on-started!+ second-config info)))
               (p/then (fn [_]
                         (delay+ 50)))
               (p/then (fn [_]
                         (is (= [{:second true}] (:sessions (read-entry dir)))
                             "A server that starts again has its own data, and nothing of what waited for the one before")))
               (p/finally (fn []
                            (cleanup! dir)
                            (done)))))))

(defn- fake-extension-context
  [calls]
  #js {:environmentVariableCollection
       #js {:replace (fn [k v] (swap! calls conj [:replace k v]))}})

(deftest window-id-env-failure-leaves-start-alone-test
  (async done
         (let [dir (tmp-dir)
               config (tmp-config dir {:vscode/extension-context
                                       #js {:environmentVariableCollection
                                            #js {:replace (fn [_ _] (throw (js/Error. "no")))}}})]
           (-> (sut/on-started!+ config (tmp-info))
               (p/then (fn [_]
                         (is (some? (read-entry dir))
                             "The entry is written although the variable couldn't be set")))
               (p/finally (fn [_]
                            (cleanup! dir)
                            (done)))))))

(deftest window-id-env-set-on-start-test
  (async done
         (let [dir (tmp-dir)
               calls (atom [])
               config (tmp-config dir {:vscode/extension-context (fake-extension-context calls)})]
           (-> (sut/on-started!+ config (tmp-info))
               (p/then (fn [_]
                         (is (= [[:replace "VSCODE_MCP_WINDOW_ID" "ws-abc"]] @calls)
                             "Start sets the window's windowId in its terminals")))
               (p/finally (fn [_]
                            (cleanup! dir)
                            (done)))))))
