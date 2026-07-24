(ns vscode-mcp.eca-config-test
  (:require
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
  (sut/desired-entry "/ext/dist/joyride-mcp-server.js"
                     "/ws/.joyride/mcp-server/port"
                     "127.0.0.1"))

(def desired'
  (sut/desired-entry "/ext/dist/joyride-mcp-server.js"
                     "/ws/.joyride/mcp-server/port"
                     "0.0.0.0"))

(deftest home-env-path-test
  (testing "under home → ${env:HOME}/..."
    (is (= "${env:HOME}/.config/calva/backseat-driver/calva-mcp-server.js"
           (sut/home-env-path "/Users/pez/.config/calva/backseat-driver/calva-mcp-server.js"
                            "/Users/pez"))))
  (testing "outside home → absolute posix unchanged"
    (is (= "/opt/mcp/wrapper.js"
           (sut/home-env-path "/opt/mcp/wrapper.js" "/Users/pez")))))

(deftest workspace-relative-path-test
  (testing "under workspace → relative"
    (is (= ".calva/mcp-server/port"
           (sut/workspace-relative-path "/ws/project/.calva/mcp-server/port"
                                        "/ws/project"))))
  (testing "outside workspace → absolute"
    (is (= "/tmp/mcp/port"
           (sut/workspace-relative-path "/tmp/mcp/port" "/ws/project")))))

(defn- parse-string-keys [text]
  (js->clj (jsonc/parse text)))

(deftest desired-entry-test
  (is (= {"command" "node"
          "args" ["/ext/dist/joyride-mcp-server.js"
                  "/ws/.joyride/mcp-server/port"
                  "127.0.0.1"]}
         desired)))

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

(deftest desired-entry-portable-wiring-test
  (testing "composes home-env-path + workspace-relative-path like eca/register!+"
    (let [entry (sut/desired-entry
                 (sut/home-env-path
                  "/Users/test/.config/calva/backseat-driver/calva-mcp-server.js"
                  "/Users/test")
                 (sut/workspace-relative-path
                  "/ws/project/.calva/mcp-server/port"
                  "/ws/project")
                 "127.0.0.1")]
      (is (= ["${env:HOME}/.config/calva/backseat-driver/calva-mcp-server.js"
              ".calva/mcp-server/port"
              "127.0.0.1"]
             (get entry "args"))))))
