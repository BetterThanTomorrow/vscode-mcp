(ns eca-entry-test
  "Starts the ECA entries that `test/vscode_mcp/eca_config_test.cljs` writes to
   `.tmp/eca-entry-fixture.json` through Java's process API, as ECA does, each against a
   stub wrapper. `bb test` runs the cljs tests first, so the fixture is there."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]))

(def ^:private fixture-path
  (str (fs/path ".tmp" "eca-entry-fixture.json")))

(def ^:private stub-wrapper
  "console.log(JSON.stringify(process.argv.slice(1)));\n")

(defn- with-stub-wrapper
  "Calls `f` with a stub wrapper at `wrapper`, in `root`, a folder created for the call
   and deleted after it."
  [{:strs [root wrapper]} f]
  (when-not (string/starts-with? (str (fs/file-name root)) "vscode-mcp-eca-test-")
    (throw (ex-info (str "Not a test folder: " root) {:root root})))
  (try
    (fs/create-dirs (fs/parent wrapper))
    (spit wrapper stub-wrapper)
    (f)
    (finally
      (fs/delete-tree root))))

(defn- run-entry
  "Returns the argv the stub wrapper saw when started by the ECA `entry`."
  [{:strs [command args]}]
  (let [{:keys [exit out err]} @(p/process {:cmd (into [command] args)
                                            :out :string
                                            :err :string})]
    (is (zero? exit) err)
    (json/parse-string out)))

(deftest eca-entry-test
  (is (fs/exists? fixture-path)
      (str fixture-path " is missing. `bb test` writes it from"
           " test/vscode_mcp/eca_config_test.cljs before it runs this test."))
  (when (fs/exists? fixture-path)
    (let [cases (json/parse-string (slurp fixture-path))]
      (is (= 3 (count cases)))
      (doseq [{case-name "name" {[_ script & wrapper-args] "args" :as entry} "entry" :as fixture-case} cases]
        (testing case-name
          (is (nil? (re-find #"[$\" \t]" script)) script)
          (with-stub-wrapper fixture-case
            #(is (= wrapper-args (run-entry entry))
                 "The wrapper sees the args after the script, intact")))))))
