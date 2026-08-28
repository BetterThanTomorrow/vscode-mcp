(ns vscode-mcp.mcp-media-test
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.mcp-media :as sut]))

(def epoch 1700000000000)

(defn- tmp-setup
  []
  (let [root (fs/mkdtempSync (path/join (os/tmpdir) "vscode-mcp-media-"))
        windows-dir (path/join root "registry" "windows")]
    (fs/mkdirSync windows-dir #js {:recursive true})
    {:root root
     :windows-dir windows-dir
     :config {:registry/dir windows-dir
              :cursor/server-name "calva-backseat-driver"}
     :info {:server/instance-slug "ws-abc"}}))

(defn- media-dir-path [{:keys [config info]}]
  (sut/media-dir config
                 (:cursor/server-name config)
                 (:server/instance-slug info)))

(defn- write-file! [file-path mtime-ms]
  (fs/mkdirSync (path/dirname file-path) #js {:recursive true})
  (fs/writeFileSync file-path "x")
  (fs/utimesSync file-path (/ mtime-ms 1000) (/ mtime-ms 1000)))

(defn- cleanup! [root]
  (sut/clear-media-state!)
  (when (and root (fs/existsSync root))
    (fs/rmSync root #js {:recursive true :force true})))

(defn- make-fake-clock
  [start-ms]
  (let [!now (atom start-ms)
        !next-id (atom 0)
        !jobs (atom {})]
    (letfn [(set-timeout [f delay]
              (let [id (swap! !next-id inc)
                    fire-at (+ @!now delay)]
                (swap! !jobs assoc id {:id id :type :timeout :f f :fire-at fire-at})
                id))
            (set-interval [f period]
              (let [id (swap! !next-id inc)
                    fire-at (+ @!now period)]
                (swap! !jobs assoc id {:id id :type :interval :f f :period period :fire-at fire-at})
                id))
            (clear-timeout [id]
              (swap! !jobs dissoc id))
            (clear-interval [id]
              (swap! !jobs dissoc id))
            (fire-due-jobs! []
              (let [due-ids (->> @!jobs
                                 (filter (fn [[_ job]]
                                           (<= (:fire-at job) @!now)))
                                 (map first)
                                 (sort-by (fn [id] (:fire-at (@!jobs id)))))]
                (doseq [id due-ids]
                  (when-let [job (get @!jobs id)]
                    ((:f job))
                    (if (= (:type job) :timeout)
                      (swap! !jobs dissoc id)
                      (swap! !jobs assoc id (assoc job :fire-at (+ @!now (:period job)))))))))
            (advance! [ms]
              (swap! !now + ms)
              (fire-due-jobs!))]
      {:now-fn (fn [] @!now)
       :set-timeout set-timeout
       :set-interval set-interval
       :clear-timeout clear-timeout
       :clear-interval clear-interval
       :advance! advance!})))

(defn- on-started-ctx [setup clock]
  (let [{:keys [config info]} setup]
    {:config config
     :server-info info
     :now-ms (:now-fn clock)
     :set-timeout (:set-timeout clock)
     :set-interval (:set-interval clock)
     :clear-timeout (:clear-timeout clock)
     :clear-interval (:clear-interval clock)}))

(defn- start-with-stale-file!
  "Writes a file older than `age-ms` and starts the media sweep."
  [setup clock]
  (let [old-file (path/join (media-dir-path setup) "old.txt")]
    (write-file! old-file (- epoch sut/age-ms 1000))
    (sut/on-started! (on-started-ctx setup clock))
    old-file))

(deftest media-dir-test
  (let [{:keys [root config info]} (tmp-setup)]
    (try
      (is (= (path/join root "mcp-media" "calva-backseat-driver-ws-abc")
             (sut/media-dir config
                            (:cursor/server-name config)
                            (:server/instance-slug info))))
      (finally
        (cleanup! root)))))

(deftest stale-files-test
  (let [setup (tmp-setup)
        root (:root setup)
        media (media-dir-path setup)
        now epoch]
    (try
      (testing "missing dir"
        (is (= [] (sut/stale-files "/nonexistent" now sut/age-ms))))
      (fs/mkdirSync media #js {:recursive true})
      (let [old (path/join media "old.txt")
            young (path/join media "young.txt")]
        (write-file! old (- now sut/age-ms 1000))
        (write-file! young (- now 1000))
        (is (= [old] (sut/stale-files media now sut/age-ms))))
      (testing "sweep on missing path"
        (is (nil? (sut/sweep! "/nonexistent" now sut/age-ms))))
      (testing "second sweep after delete"
        (sut/sweep! media now sut/age-ms)
        (is (nil? (sut/sweep! media now sut/age-ms))))
      (finally
        (cleanup! root)))))

(deftest delay-keeps-old-files-test
  (let [setup (tmp-setup)
        root (:root setup)
        clock (make-fake-clock epoch)]
    (try
      (let [old-file (start-with-stale-file! setup clock)]
        ((:advance! clock) (dec sut/delay-ms))
        (is (fs/existsSync old-file)))
      (finally
        (cleanup! root)))))

(deftest sweep-after-delay-test
  (let [setup (tmp-setup)
        root (:root setup)
        clock (make-fake-clock epoch)
        media (media-dir-path setup)
        old-file (path/join media "old.txt")
        young-file (path/join media "young.txt")]
    (try
      (write-file! old-file (- epoch 1000))
      (write-file! young-file (+ epoch 540000))
      (sut/on-started! (on-started-ctx setup clock))
      ((:advance! clock) sut/delay-ms)
      (is (not (fs/existsSync old-file)))
      (is (fs/existsSync young-file))
      (is (fs/existsSync media))
      (finally
        (cleanup! root)))))

(deftest missing-path-sweep-test
  (is (nil? (sut/sweep! "/nonexistent-dir" epoch sut/age-ms))))

(deftest on-stopping-clears-timers-test
  (let [setup (tmp-setup)
        root (:root setup)
        clock (make-fake-clock epoch)]
    (try
      (let [old-file (start-with-stale-file! setup clock)]
        (sut/on-stopping! {:config (:config setup) :server-info (:info setup)})
        ((:advance! clock) sut/delay-ms)
        (is (fs/existsSync old-file)))
      (finally
        (cleanup! root)))))
