(ns vscode-mcp.registry-writer-test
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [cljs.test :refer [deftest is]]
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
  (js/Promise. (fn [resolve]
                (js/setTimeout resolve ms))))

(defn- cleanup! [dir]
  (sut/clear-writers!)
  (when (and dir (fs/existsSync dir))
    (fs/rmSync dir #js {:recursive true :force true})))

(deftest ^:async on-started-writes-entry-test
  (let [dir (tmp-dir)
        config (tmp-config dir {:registry/custom-data {:sessions [{:replSessionKey "clj"}]}})]
    (try
      (await (sut/on-started!+ config (tmp-info)))
      (let [doc (read-entry dir)]
        (is (= [{:replSessionKey "clj"}] (:sessions doc))
            "The entry of a server that starts has the custom data in the config")
        (is (= 1 (:schemaVersion doc)))
        (is (= "backseat-driver-ws-abc" (:name doc)))
        (is (= "backseat-driver" (:serverName doc)))
        (is (= "ws-abc" (:windowId doc)))
        (is (= "cursor" (:appId doc)))
        (is (= "/proj" (:workspaceRoot doc)))
        (is (= "/proj" (:workspaceFolder doc))))
      (finally
        (cleanup! dir)))))

(deftest ^:async custom-data-cannot-overwrite-the-entrys-own-fields-test
  (let [dir (tmp-dir)
        config (tmp-config dir {:registry/custom-data {:serverName "impostor" :pid 1 :extra "kept"}})]
    (try
      (await (sut/on-started!+ config (tmp-info)))
      (let [doc (read-entry dir)]
        (is (= "backseat-driver" (:serverName doc)))
        (is (not= 1 (:pid doc)))
        (is (= "kept" (:extra doc))))
      (finally
        (cleanup! dir)))))

(deftest ^:async debounce-coalesces-refreshes-test
  (let [dir (tmp-dir)
        config (fn [sessions]
                 (tmp-config dir {:registry/custom-data {:sessions sessions}}))]
    (try
      (await (sut/on-started!+ (config ["a"]) (tmp-info)))
      (is (= ["a"] (:sessions (read-entry dir))))
      (sut/update-registry!+ (config ["b"]))
      (sut/update-registry!+ (config ["c"]))
      (is (= ["a"] (:sessions (read-entry dir)))
          "A change is written after the debounce time, and not at once")
      (await (delay+ 50))
      (is (= ["c"] (:sessions (read-entry dir)))
          "Two changes inside the debounce time give one write, with the later data")
      (finally
        (cleanup! dir)))))

(deftest ^:async a-write-that-waits-does-nothing-after-the-stop-test
  (let [dir (tmp-dir)
        config (tmp-config dir {:registry/custom-data {:sessions ["a"]}})
        info (tmp-info)]
    (try
      (await (sut/on-started!+ config info))
      (sut/update-registry!+ (assoc config :registry/custom-data {:sessions ["late"]}))
      (sut/on-stopping! config)
      (sut/on-stopped! config info)
      (await (delay+ 50))
      (is (not (fs/existsSync (entry-path dir)))
          "A change that waited to be written when the server stopped doesn't bring the entry back")
      (finally
        (cleanup! dir)))))

(deftest ^:async heartbeat-bumps-updated-at-test
  (let [dir (tmp-dir)
        config (tmp-config dir {:registry/heartbeat-ms 40
                                :registry/custom-data {:sessions []}})
        info (tmp-info)]
    (try
      (await (sut/on-started!+ config info))
      (let [first-at (:updatedAt (read-entry dir))]
        (await (delay+ 90))
        (let [doc (read-entry dir)]
          (is (some? first-at))
          (is (not= first-at (:updatedAt doc))
              "The heartbeat writes the entry with a new time")
          (is (= [] (:sessions doc))
              "and the same data")
          (sut/on-stopping! config)
          (sut/on-stopped! config info)
          (await (delay+ 90))
          (is (not (fs/existsSync (entry-path dir)))
              "A heartbeat after the stop writes nothing")))
      (finally
        (cleanup! dir)))))

(deftest ^:async stop-then-start-writes-the-new-servers-entry-test
  (let [dir (tmp-dir)
        info (tmp-info)
        first-config (tmp-config dir {:registry/custom-data {:sessions [{:first true}]}})
        second-config (tmp-config dir {:registry/custom-data {:sessions [{:second true}]}})]
    (try
      (await (sut/on-started!+ first-config info))
      (sut/update-registry!+ (assoc first-config :registry/custom-data {:sessions [{:late true}]}))
      (sut/on-stopping! first-config)
      (sut/on-stopped! first-config info)
      (await (sut/on-started!+ second-config info))
      (await (delay+ 50))
      (is (= [{:second true}] (:sessions (read-entry dir)))
          "A server that starts again has its own data, and nothing of what waited for the one before")
      (finally
        (cleanup! dir)))))

(defn- fake-extension-context
  [calls]
  #js {:environmentVariableCollection
       #js {:replace (fn [k v] (swap! calls conj [:replace k v]))}})

(deftest ^:async window-id-env-failure-leaves-start-alone-test
  (let [dir (tmp-dir)
        config (tmp-config dir {:vscode/extension-context
                                #js {:environmentVariableCollection
                                     #js {:replace (fn [_ _] (throw (js/Error. "no")))}}})]
    (try
      (await (sut/on-started!+ config (tmp-info)))
      (is (some? (read-entry dir))
          "The entry is written although the variable couldn't be set")
      (finally
        (cleanup! dir)))))

(deftest ^:async window-id-env-set-on-start-test
  (let [dir (tmp-dir)
        calls (atom [])
        config (tmp-config dir {:vscode/extension-context (fake-extension-context calls)})]
    (try
      (await (sut/on-started!+ config (tmp-info)))
      (is (= [[:replace "VSCODE_MCP_WINDOW_ID" "ws-abc"]] @calls)
          "Start sets the window's windowId in its terminals")
      (finally
        (cleanup! dir)))))
