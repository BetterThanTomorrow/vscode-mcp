(ns eca-entry-test
  "Starts the ECA entry's `node -e` command through Java's process API, as ECA does,
   against a stub wrapper. The scripts mirror `vscode-mcp.eca-config/wrapper-require-script`."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]))

(def ^:private stub-wrapper
  "console.log(JSON.stringify(process.argv.slice(1)));\n")

(defn- js-string-literal
  [s]
  (str "'"
       (string/replace s #"[^A-Za-z0-9._/:-]"
                       (fn [c] (format "\\u%04x" (int (first c)))))
       "'"))

(defn- home-script
  [segments]
  (str "require(require('path').join(require('os').homedir(),"
       (string/join "," (map js-string-literal segments))
       "))"))

(defn- absolute-script
  [wrapper-path]
  (str "require(" (js-string-literal (string/replace (str wrapper-path) "\\" "/")) ")"))

(defn- node-home []
  (string/trim (:out (p/shell {:out :string} "node" "-p" "require('os').homedir()"))))

(defn- write-stub! [dir]
  (let [wrapper (fs/path dir "calva-mcp-server.js")]
    (spit (str wrapper) stub-wrapper)
    wrapper))

(defn- run-entry
  "Returns the argv the stub wrapper saw when started as `node -e script & args`."
  [script args]
  (let [{:keys [exit out err]} @(p/process {:cmd (into ["node" "-e" script] args)
                                             :out :string
                                             :err :string})]
    (is (zero? exit) err)
    (json/parse-string out)))

(def ^:private registry-off-args
  ["calva-mcp-server.js"
   "C:\\Users\\Some One\\.config\\vscode-mcp\\port-files\\calva-backseat-driver-ws-abc.port"
   "127.0.0.1"])

(deftest home-joined-entry-test
  (let [dir (fs/create-temp-dir {:dir (node-home) :prefix "vscode-mcp-eca-test-"})]
    (try
      (let [wrapper (write-stub! dir)
            script (home-script (map str (fs/relativize (node-home) wrapper)))]
        (is (nil? (re-find #"[$\" \t]" script)) script)
        (testing "registry on: the wrapper sees its file name only"
          (is (= ["calva-mcp-server.js"]
                 (run-entry script ["calva-mcp-server.js"]))))
        (testing "registry off: the port file and host follow, intact"
          (is (= registry-off-args
                 (run-entry script registry-off-args)))))
      (finally
        (fs/delete-tree dir)))))

(deftest absolute-entry-test
  (let [dir (fs/create-dirs (fs/path (fs/create-temp-dir) "eca entry's $dir"))]
    (try
      (let [wrapper (write-stub! dir)
            script (absolute-script wrapper)]
        (is (nil? (re-find #"[$\" \t]" script)) script)
        (is (= ["calva-mcp-server.js"]
               (run-entry script ["calva-mcp-server.js"]))
            "escaped characters in the wrapper path decode in node"))
      (finally
        (fs/delete-tree (fs/parent dir))))))
