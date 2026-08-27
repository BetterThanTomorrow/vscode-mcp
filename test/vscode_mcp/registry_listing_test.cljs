(ns vscode-mcp.registry-listing-test
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [cljs.test :refer [async deftest is testing]]
   [promesa.core :as p]
   [vscode-mcp.registry-listing :as sut]
   [vscode-mcp.registry-listing-stubs :as stubs]))

(defn- tmp-root []
  (fs/mkdtempSync (path/join (os/tmpdir) "vscode-mcp-listing-")))

(defn- fake-ctx [ext-path]
  #js {:extensionPath ext-path})

(defn- listing-config
  [root extra]
  (merge {:registry/enabled? true
          :registry/dir (path/join root "registry" "windows")
          :vscode/extension-context (fake-ctx (path/join root "ext"))}
         extra))

(defn- write-sibling-tree!
  [root]
  (let [dir (path/join root "vscode-mcp" "assets" "registry-content")
        scripts (path/join dir "scripts")
        fallback (path/join dir "fallback")]
    (fs/mkdirSync scripts #js {:recursive true})
    (fs/mkdirSync fallback #js {:recursive true})
    (fs/writeFileSync (path/join dir "README.md") "sibling-readme\n")
    (fs/writeFileSync (path/join dir "AGENTS.md") "sibling-agents\n")
    (fs/writeFileSync (path/join dir "bb.edn") "{:paths [\"scripts\"]}\n")
    (fs/writeFileSync (path/join scripts "list_registry.clj") "(ns list-registry)\n")
    (fs/writeFileSync (path/join fallback "README.md") "must-not-copy\n")
    dir))

(defn- github-files []
  [["README.md" "github-readme\n"]
   ["AGENTS.md" "github-agents\n"]
   ["bb.edn" "{:paths [\"scripts\"]}\n"]
   ["scripts/list_registry.clj" "(ns list-registry)\n"]])

(defn- install-with-fetch!+
  [root fetch-fn]
  (let [config (listing-config root {})]
    (reset! sut/!fetch-override!+ fetch-fn)
    (p/let [result (sut/maybe-install!+ config)]
      {:result result
       :home (sut/registry-home config)})))

(defn- cleanup! [root]
  (sut/clear-install-state!)
  (when (and root (fs/existsSync root))
    (fs/rmSync root #js {:recursive true :force true})))

(deftest write-if-changed-test
  (let [root (tmp-root)
        f (path/join root "f.txt")]
    (try
      (is (true? (sut/write-if-changed! f "a")))
      (is (nil? (sut/write-if-changed! f "a")))
      (is (true? (sut/write-if-changed! f "b")))
      (is (= "b" (fs/readFileSync f "utf8")))
      (finally
        (cleanup! root)))))

(deftest sibling-copy-writes-consumer-files-only-test
  (async done
         (let [root (tmp-root)
               _ (write-sibling-tree! root)
               config (listing-config root {})
               home (sut/registry-home config)]
           (-> (sut/maybe-install!+ config)
               (p/then (fn [result]
                         (is (= :sibling result))
                         (is (= "sibling-readme\n" (fs/readFileSync (path/join home "README.md") "utf8")))
                         (is (= "sibling-agents\n" (fs/readFileSync (path/join home "AGENTS.md") "utf8")))
                         (is (fs/existsSync (path/join home "bb.edn")))
                         (is (fs/existsSync (path/join home "scripts" "list_registry.clj")))
                         (is (not (fs/existsSync (path/join home "fallback"))))))
               (p/finally (fn []
                            (cleanup! root)
                            (done)))))))

(deftest github-fetch-test
  (async done
         (let [ok-root (tmp-root)
               fail-root (tmp-root)]
           (-> (install-with-fetch!+ ok-root #(p/resolved (github-files)))
               (p/then (fn [{:keys [result home]}]
                         (is (= :github result))
                         (is (= "github-readme\n" (fs/readFileSync (path/join home "README.md") "utf8")))
                         (is (fs/existsSync (path/join home "bb.edn")))
                         (reset! sut/!install-ok? false)
                         (install-with-fetch!+ fail-root #(p/rejected (js/Error. "offline")))))
               (p/then (fn [{:keys [result home]}]
                         (is (= :github-failed result))
                         (is (= stubs/readme (fs/readFileSync (path/join home "README.md") "utf8")))
                         (is (= stubs/agents (fs/readFileSync (path/join home "AGENTS.md") "utf8")))
                         (is (not (fs/existsSync (path/join home "bb.edn"))))))
               (p/finally (fn []
                            (cleanup! ok-root)
                            (cleanup! fail-root)
                            (done)))))))

(deftest github-fail-leaves-previous-install-test
  (async done
         (let [root (tmp-root)
               config (listing-config root {})
               home (sut/registry-home config)]
           (fs/mkdirSync home #js {:recursive true})
           (fs/writeFileSync (path/join home "README.md") "real-readme\n")
           (fs/writeFileSync (path/join home "bb.edn") "{:paths [\"scripts\"]}\n")
           (reset! sut/!fetch-override!+ (fn [] (p/rejected (js/Error. "offline"))))
           (-> (sut/maybe-install!+ config)
               (p/then (fn [result]
                         (is (= :github-failed result))
                         (is (= "real-readme\n" (fs/readFileSync (path/join home "README.md") "utf8")))
                         (is (not= stubs/readme (fs/readFileSync (path/join home "README.md") "utf8")))))
               (p/finally (fn []
                            (cleanup! root)
                            (done)))))))

(deftest success-flag-skips-later-install-test
  (async done
         (let [root (tmp-root)
               config (listing-config root {})
               calls (atom 0)]
           (write-sibling-tree! root)
           (reset! sut/!fetch-override!+ (fn []
                                           (swap! calls inc)
                                           (p/rejected (js/Error. "should-not-run"))))
           (-> (sut/maybe-install!+ config)
               (p/then (fn [_] (sut/maybe-install!+ config)))
               (p/then (fn [result]
                         (is (= :skipped result))
                         (is (= 0 @calls))))
               (p/finally (fn []
                            (cleanup! root)
                            (done)))))))

(deftest failed-fetch-retries-on-next-start-test
  (async done
         (let [root (tmp-root)
               config (listing-config root {})
               home (sut/registry-home config)
               calls (atom 0)]
           (reset! sut/!fetch-override!+ (fn []
                                           (swap! calls inc)
                                           (if (= 1 @calls)
                                             (p/rejected (js/Error. "offline"))
                                             (p/resolved (github-files)))))
           (-> (sut/maybe-install!+ config)
               (p/then (fn [result]
                         (is (= :github-failed result))
                         (sut/maybe-install!+ config)))
               (p/then (fn [result]
                         (is (= :github result))
                         (is (= 2 @calls))
                         (is (= "github-readme\n" (fs/readFileSync (path/join home "README.md") "utf8")))))
               (p/finally (fn []
                            (cleanup! root)
                            (done)))))))

(deftest disabled-registry-skips-test
  (async done
         (let [root (tmp-root)
               config (listing-config root {:registry/enabled? false})]
           (-> (sut/maybe-install!+ config)
               (p/then (fn [result]
                         (is (= :skipped result))
                         (is (not (fs/existsSync (path/join (sut/registry-home config) "README.md"))))))
               (p/finally (fn []
                            (cleanup! root)
                            (done)))))))

(deftest stubs-are-inlined-from-fallback-assets-test
  (testing "inlined stubs match the pez-authored fallback files"
    (is (string? stubs/readme))
    (is (string? stubs/agents))
    (is (re-find #"GitHub was unreachable" stubs/readme))
    (is (re-find #"windows/\*\.json" stubs/agents))))
