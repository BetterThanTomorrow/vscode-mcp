(ns vscode-mcp.registry-listing-test
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [cljs.test :refer [deftest is testing]]
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
    (fs/writeFileSync (path/join dir "bb-mcp.md") "sibling-bb-mcp\n")
    (fs/writeFileSync (path/join dir "bb.edn") "{:paths [\"scripts\"]}\n")
    (fs/writeFileSync (path/join scripts "list_registry.clj") "(ns list-registry)\n")
    (fs/writeFileSync (path/join scripts "mcp.clj") "(ns mcp)\n")
    (fs/writeFileSync (path/join scripts "mcp_briefing.clj") "(ns mcp-briefing)\n")
    (fs/writeFileSync (path/join fallback "README.md") "must-not-copy\n")
    dir))

(defn- github-files []
  [["README.md" "github-readme\n"]
   ["AGENTS.md" "github-agents\n"]
   ["bb-mcp.md" "github-bb-mcp\n"]
   ["bb.edn" "{:paths [\"scripts\"]}\n"]
   ["scripts/list_registry.clj" "(ns list-registry)\n"]
   ["scripts/mcp.clj" "(ns mcp)\n"]
   ["scripts/mcp_briefing.clj" "(ns mcp-briefing)\n"]])

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

(deftest ^:async sibling-copy-writes-consumer-files-only-test
  (let [root (tmp-root)
        _ (write-sibling-tree! root)
        config (listing-config root {})
        home (sut/registry-home config)]
    (try
      (let [result (await (sut/maybe-install!+ config))]
        (is (= :sibling result))
        (is (every? #(fs/existsSync (path/join home %)) sut/consumer-files))
        (is (= "sibling-bb-mcp\n" (fs/readFileSync (path/join home "bb-mcp.md") "utf8")))
        (is (= "(ns mcp)\n" (fs/readFileSync (path/join home "scripts" "mcp.clj") "utf8")))
        (is (not (fs/existsSync (path/join home "fallback")))))
      (finally
        (cleanup! root)))))

(deftest ^:async github-fetch-test
  (let [ok-root (tmp-root)
        fail-root (tmp-root)]
    (try
      (let [{:keys [result home]} (await (install-with-fetch!+ ok-root #(p/resolved (github-files))))]
        (is (= :github result))
        (is (= "github-readme\n" (fs/readFileSync (path/join home "README.md") "utf8")))
        (is (fs/existsSync (path/join home "bb.edn"))))
      (reset! sut/!install-ok? false)
      (let [{:keys [result home]} (await (install-with-fetch!+ fail-root #(p/rejected (js/Error. "offline"))))]
        (is (= :github-failed result))
        (is (= stubs/readme (fs/readFileSync (path/join home "README.md") "utf8")))
        (is (= stubs/agents (fs/readFileSync (path/join home "AGENTS.md") "utf8")))
        (is (not (fs/existsSync (path/join home "bb.edn")))))
      (finally
        (cleanup! ok-root)
        (cleanup! fail-root)))))

(deftest ^:async github-fail-leaves-previous-install-test
  (let [root (tmp-root)
        config (listing-config root {})
        home (sut/registry-home config)]
    (fs/mkdirSync home #js {:recursive true})
    (fs/writeFileSync (path/join home "README.md") "real-readme\n")
    (fs/writeFileSync (path/join home "bb.edn") "{:paths [\"scripts\"]}\n")
    (reset! sut/!fetch-override!+ (fn [] (p/rejected (js/Error. "offline"))))
    (try
      (let [result (await (sut/maybe-install!+ config))]
        (is (= :github-failed result))
        (is (= "real-readme\n" (fs/readFileSync (path/join home "README.md") "utf8")))
        (is (not= stubs/readme (fs/readFileSync (path/join home "README.md") "utf8"))))
      (finally
        (cleanup! root)))))

(deftest ^:async success-flag-skips-later-install-test
  (let [root (tmp-root)
        config (listing-config root {})
        calls (atom 0)]
    (write-sibling-tree! root)
    (reset! sut/!fetch-override!+ (fn []
                                    (swap! calls inc)
                                    (p/rejected (js/Error. "should-not-run"))))
    (try
      (await (sut/maybe-install!+ config))
      (let [result (await (sut/maybe-install!+ config))]
        (is (= :skipped result))
        (is (= 0 @calls)))
      (finally
        (cleanup! root)))))

(deftest ^:async failed-fetch-retries-on-next-start-test
  (let [root (tmp-root)
        config (listing-config root {})
        home (sut/registry-home config)
        calls (atom 0)]
    (reset! sut/!fetch-override!+ (fn []
                                    (swap! calls inc)
                                    (if (= 1 @calls)
                                      (p/rejected (js/Error. "offline"))
                                      (p/resolved (github-files)))))
    (try
      (let [result (await (sut/maybe-install!+ config))]
        (is (= :github-failed result)))
      (let [result (await (sut/maybe-install!+ config))]
        (is (= :github result))
        (is (= 2 @calls))
        (is (= "github-readme\n" (fs/readFileSync (path/join home "README.md") "utf8"))))
      (finally
        (cleanup! root)))))

(deftest ^:async disabled-registry-skips-test
  (let [root (tmp-root)
        config (listing-config root {:registry/enabled? false})]
    (try
      (let [result (await (sut/maybe-install!+ config))]
        (is (= :skipped result))
        (is (not (fs/existsSync (path/join (sut/registry-home config) "README.md")))))
      (finally
        (cleanup! root)))))

(deftest stubs-are-inlined-from-fallback-assets-test
  (testing "inlined stubs match the pez-authored fallback files"
    (is (string? stubs/readme))
    (is (string? stubs/agents))
    (is (re-find #"GitHub was unreachable" stubs/readme))
    (is (re-find #"windows/\*\.json" stubs/agents))))
