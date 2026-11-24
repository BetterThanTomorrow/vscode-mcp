(ns vscode-mcp.eca-config-test
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [vscode-mcp.eca-config :as sut]
   [vscode-mcp.jsonc :as jsonc]))

(def sample-jsonc
  (str "{\n"
       "  // keep me\n"
       "  \"providers\": {\n"
       "    \"openrouter\": {\"key\": \"secret\"}\n"
       "  },\n"
       "  \"$schema\": \"https://eca.dev/config.json\"\n"
       "}\n"))

(def desired
  (sut/desired-entry {:os/home-dir "/home/test"
                      :os/platform "linux"
                      :wrapper/path "/home/test/.config/joyride/joyride-mcp-server.js"
                      :registry/enabled? true}))

(def desired'
  (sut/desired-entry {:os/home-dir "/home/test"
                      :os/platform "linux"
                      :wrapper/path "/home/test/.config/joyride/joyride-mcp-server.js"
                      :registry/enabled? false
                      :server/port-file-path "/home/test/.config/vscode-mcp/port-files/joyride-ws-abc.port"
                      :server/host "0.0.0.0"}))

(defn- parse-string-keys [text]
  (js->clj (jsonc/parse text)))

(deftest desired-entry-test
  (testing "with the window registry on, the args hold no machine-specific path"
    (is (= {"command" "node"
            "args" ["-e"
                    "require(require('path').join(require('os').homedir(),'.config','calva','backseat-driver','calva-mcp-server.js'))"
                    "calva-mcp-server.js"]}
           (sut/desired-entry
            {:os/home-dir "/Users/test"
             :os/platform "darwin"
             :wrapper/path "/Users/test/.config/calva/backseat-driver/calva-mcp-server.js"
             :registry/enabled? true
             :server/port-file-path "/Users/test/.config/vscode-mcp/port-files/calva-backseat-driver-ws-abc.port"
             :server/host "127.0.0.1"})))))

(deftest plan-creates-entry-from-blank-test
  (testing "missing / blank / {}"
    (doseq [text [nil "" "{}"]]
      (let [plan (sut/plan-config-text text "joyride" desired)]
        (is (= :write (:eca/action plan))
            (str "writes for " (pr-str text)))
        (is (= desired
               (get-in (parse-string-keys (:eca/text plan)) ["mcpServers" "joyride"])))))))

(deftest plan-preserves-siblings-and-comments-test
  (testing "entry missing; other roots/comments present"
    (let [plan (sut/plan-config-text sample-jsonc "joyride" desired)
          text (:eca/text plan)
          data (parse-string-keys text)]
      (is (= :write (:eca/action plan)))
      (is (str/includes? text "// keep me"))
      (is (= "secret" (get-in data ["providers" "openrouter" "key"])))
      (is (= "https://eca.dev/config.json" (get data "$schema")))
      (is (= desired (get-in data ["mcpServers" "joyride"])))
      (is (not (contains? (get-in data ["mcpServers" "joyride"]) "$schema"))
          "does not inject $schema into the entry"))))

(deftest plan-no-op-when-managed-fields-equal-test
  (testing "managed fields equal (siblings may differ)"
    (let [with-entry (jsonc/assoc-in-text sample-jsonc
                                          ["mcpServers" "joyride"]
                                          (merge desired {"env" {"FOO" "bar"}
                                                          "disabled" false}))
          plan (sut/plan-config-text with-entry "joyride" desired)]
      (is (= :no-op (:eca/action plan)))
      (is (= with-entry (:eca/text plan))
          "no rewrite when managed fields already match"))))

(deftest plan-merges-when-managed-fields-differ-test
  (testing "command or args differ; preserve env / disabled"
    (let [existing (merge desired
                          {"env" {"FOO" "bar"}
                           "disabled" true})
          with-entry (jsonc/assoc-in-text sample-jsonc ["mcpServers" "joyride"] existing)
          plan (sut/plan-config-text with-entry "joyride" desired')
          data (parse-string-keys (:eca/text plan))
          entry (get-in data ["mcpServers" "joyride"])]
      (is (= :write (:eca/action plan)))
      (is (= desired' (select-keys entry sut/managed-fields)))
      (is (= {"FOO" "bar"} (get entry "env")))
      (is (true? (get entry "disabled")))
      (is (str/includes? (:eca/text plan) "// keep me")))))

(deftest plan-second-apply-is-no-op-test
  (testing "second apply of same desired"
    (let [once (sut/plan-config-text sample-jsonc "joyride" desired)
          twice (sut/plan-config-text (:eca/text once) "joyride" desired)]
      (is (= :write (:eca/action once)))
      (is (= :no-op (:eca/action twice)))
      (is (= (:eca/text once) (:eca/text twice))))))

(defn- required-path
  "Runs `script` with a stub `require` and returns the path it requires the wrapper from."
  [script path-module home-dir]
  (let [!required (atom nil)
        stub-require (fn [module-name]
                       (case module-name
                         "path" path-module
                         "os" #js {:homedir (constantly home-dir)}
                         (reset! !required module-name)))]
    ((js/Function. "require" script) stub-require)
    @!required))

(deftest wrapper-require-script-test
  (testing "wrapper under home on POSIX: joined under os.homedir()"
    (is (= "require(require('path').join(require('os').homedir(),'.config','calva','backseat-driver','calva-mcp-server.js'))"
           (sut/wrapper-require-script
            {:os/home-dir "/Users/test"
             :os/platform "darwin"
             :wrapper/path "/Users/test/.config/calva/backseat-driver/calva-mcp-server.js"}))))
  (testing "wrapper under home on Windows, drive letter in another case"
    (let [script (sut/wrapper-require-script
                  {:os/home-dir "C:\\Users\\Test"
                   :os/platform "win32"
                   :wrapper/path "c:\\Users\\Test\\.config\\calva\\backseat-driver\\calva-mcp-server.js"})]
      (is (= "require(require('path').join(require('os').homedir(),'.config','calva','backseat-driver','calva-mcp-server.js'))"
             script))
      (is (= "C:\\Users\\Other\\.config\\calva\\backseat-driver\\calva-mcp-server.js"
             (required-path script path/win32 "C:\\Users\\Other"))
          "another user's machine finds the wrapper under its own home")))
  (testing "wrapper outside home: absolute path with forward slashes"
    (is (= "require('/opt/mcp/calva-mcp-server.js')"
           (sut/wrapper-require-script
            {:os/home-dir "/home/test"
             :os/platform "linux"
             :wrapper/path "/opt/mcp/calva-mcp-server.js"})))
    (is (= "require('/home/tester/calva-mcp-server.js')"
           (sut/wrapper-require-script
            {:os/home-dir "/home/test"
             :os/platform "linux"
             :wrapper/path "/home/tester/calva-mcp-server.js"}))
        "a sibling of home that shares its prefix is outside home")
    (is (= "require('D:/tools/mcp/calva-mcp-server.js')"
           (sut/wrapper-require-script
            {:os/home-dir "C:\\Users\\Test"
             :os/platform "win32"
             :wrapper/path "D:\\tools\\mcp\\calva-mcp-server.js"}))
        "another drive on Windows")))

(deftest wrapper-require-script-characters-test
  (let [cases [{:os/home-dir "/home/test"
                :os/platform "linux"
                :wrapper/path "/home/test/my tools/it's $HOME \"q\"\tx\\y/calva-mcp-server.js"}
               {:os/home-dir "/home/test"
                :os/platform "linux"
                :wrapper/path "/opt/my tools/$x/calva-mcp-server.js"}
               {:os/home-dir "C:\\Users\\Test"
                :os/platform "win32"
                :wrapper/path "C:\\Users\\Test\\my tools\\it's $x\\calva-mcp-server.js"}
               {:os/home-dir "C:\\Users\\Test"
                :os/platform "win32"
                :wrapper/path "C:\\Program Files\\mcp\\calva-mcp-server.js"}]]
    (doseq [{:os/keys [home-dir platform]
             wrapper-path :wrapper/path
             :as script-opts} cases
            :let [script (sut/wrapper-require-script script-opts)
                  path-module (if (= "win32" platform) path/win32 path/posix)]]
      (is (nil? (re-find #"[$\" \t]" script))
          (str "no $, double quote, space or tab in " script))
      (is (= (.normalize path-module wrapper-path)
             (.normalize path-module (required-path script path-module home-dir)))
          (str "the script requires " wrapper-path)))))

(deftest desired-entry-registry-off-test
  (testing "with the window registry off, the args end with the port file and the host"
    (is (= {"command" "node"
            "args" ["-e"
                    "require(require('path').join(require('os').homedir(),'.config','calva','backseat-driver','calva-mcp-server.js'))"
                    "calva-mcp-server.js"
                    "c:\\Users\\Test\\.config\\vscode-mcp\\port-files\\calva-backseat-driver-ws-abc.port"
                    "0.0.0.0"]}
           (sut/desired-entry
            {:os/home-dir "C:\\Users\\Test"
             :os/platform "win32"
             :wrapper/path "C:\\Users\\Test\\.config\\calva\\backseat-driver\\calva-mcp-server.js"
             :registry/enabled? false
             :server/port-file-path "c:\\Users\\Test\\.config\\vscode-mcp\\port-files\\calva-backseat-driver-ws-abc.port"
             :server/host "0.0.0.0"}))))
  (testing "blank host falls back to the default host"
    (is (= "127.0.0.1"
           (last (get (sut/desired-entry
                       {:os/home-dir "/home/test"
                        :os/platform "linux"
                        :wrapper/path "/home/test/.config/joyride/joyride-mcp-server.js"
                        :registry/enabled? false
                        :server/port-file-path "/home/test/.config/vscode-mcp/port-files/joyride-ws-abc.port"
                        :server/host nil})
                      "args"))))))

(deftest plan-pretty-prints-owned-entry-test
  (testing "blank → write with indent depth 2"
    (let [plan (sut/plan-config-text nil "joyride" desired)
          text (:eca/text plan)]
      (is (= :write (:eca/action plan)))
      (is (str/includes? text "\n  \"mcpServers\""))
      (is (str/includes? text "\n      \"command\": \"node\""))))

  (testing "existing 4-space file matches indent on write"
    (let [four-space (str "{\n"
                          "    \"$schema\": \"https://eca.dev/config.json\"\n"
                          "}\n")
          plan (sut/plan-config-text four-space "joyride" desired)
          text (:eca/text plan)]
      (is (= :write (:eca/action plan)))
      (is (str/includes? text "\n    \"mcpServers\""))
      (is (str/includes? text "\n            \"command\": \"node\"")))))

(def eca-entry-fixture-path
  "Where the ECA entries `test-bb/eca_entry_test.clj` starts are written."
  (path/join (js/process.cwd) ".tmp" "eca-entry-fixture.json"))

(defn- eca-entry-case
  "A case for `test-bb/eca_entry_test.clj`: the ECA entry for a stub wrapper in
   `wrapper-dir` under `root`, a folder the bb test creates and deletes."
  [case-name root wrapper-dir entry-opts]
  (let [wrapper-path (path/join wrapper-dir "calva-mcp-server.js")]
    {"name" case-name
     "root" root
     "wrapper" wrapper-path
     "entry" (sut/desired-entry (assoc entry-opts
                                      :os/platform js/process.platform
                                      :wrapper/path wrapper-path))}))

(deftest eca-entry-fixture-test
  (let [id (str "vscode-mcp-eca-test-" js/process.pid "-" (js/Date.now))
        home (os/homedir)
        home-root (path/join home id)
        home-dir (path/join home-root "😀 home")
        outside-root (path/join (os/tmpdir) id)
        outside-dir (path/join outside-root "eca entry's $dir 😀")
        cases [(eca-entry-case "under home, registry on" home-root home-dir
                               {:os/home-dir home
                                :registry/enabled? true})
               (eca-entry-case "under home, registry off" home-root home-dir
                               {:os/home-dir home
                                :registry/enabled? false
                                :server/port-file-path "C:\\Users\\Some One\\.config\\vscode-mcp\\port-files\\calva-backseat-driver-ws-abc.port"
                                :server/host "127.0.0.1"})
               (eca-entry-case "outside home" outside-root outside-dir
                               {:os/home-dir (path/join outside-root "home")
                                :registry/enabled? true})]]
    (is (= [true true false]
           (map #(str/includes? (get-in % ["entry" "args" 1]) "homedir()") cases))
        "The bb test starts both forms of the script")
    (fs/mkdirSync (path/dirname eca-entry-fixture-path) #js {:recursive true})
    (fs/writeFileSync eca-entry-fixture-path (js/JSON.stringify (clj->js cases) nil 2))))
