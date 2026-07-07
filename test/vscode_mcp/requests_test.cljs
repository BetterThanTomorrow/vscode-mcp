(ns vscode-mcp.requests-test
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [cljs.test :refer [async deftest is testing]]
   [clojure.string :as string]
   [promesa.core :as p]
   [vscode-mcp.requests :as sut]))

(def ^:private skill-fixture-path "stubs/skills/test-skill/SKILL.md")

(defn- test-fixture-extension-path []
  (some (fn [root]
          (let [candidate (path/join root skill-fixture-path)]
            (when (fs/existsSync candidate)
              root)))
        [(path/join (js/process.cwd) "test")
         (path/join (js/process.cwd) "../vscode-mcp/test")
         (path/join js/__dirname "../test")]))

(defn- mock-skill-context [extension-path]
  #js {:extensionPath extension-path
       :extension #js {:packageJSON #js {:name "test-ext"
                                         :version "1.2.3"
                                         :contributes #js {:chatSkills #js [#js {:path "stubs/skills/test-skill/SKILL.md"}]}}}})

(defn- mock-tool-context [tools]
  #js {:extension #js {:packageJSON #js {:name "test-ext"
                                         :version "0.1.0"
                                         :contributes #js {:languageModelTools tools}}}})

(deftest initialize-test
  (let [ctx (mock-tool-context #js [#js {:name "t" :modelDescription "Tool"}])]
    (testing "builds initialize result from manifest"
      (let [res (sut/handle-manifest-request ctx {:method "initialize" :id 1} {:settings {}})]
        (is (= "2024-11-05" (get-in res [:result :protocolVersion])))
        (is (= "test-ext" (get-in res [:result :serverInfo :name])))))

    (testing "initialize-opts and initialize-merge"
      (let [res (sut/handle-manifest-request ctx {:method "initialize" :id 2}
                                             {:settings {}
                                              :initialize-opts {:name "custom" :base-text "Hello"}
                                              :initialize-merge {:description "Extra"}})]
        (is (= "custom" (get-in res [:result :serverInfo :name])))
        (is (string/includes? (get-in res [:result :instructions]) "Hello"))
        (is (= "Extra" (get-in res [:result :description])))))))

(deftest tools-list-test
  (testing "respects when settings"
    (let [ctx (mock-tool-context #js [#js {:name "gated" :modelDescription "G" :when "config.on"}
                                       #js {:name "open" :modelDescription "O"}])]
      (is (= 2 (count (get-in (sut/handle-manifest-request ctx {:method "tools/list" :id 1}
                                                               {:settings {"config.on" true}})
                              [:result :tools]))))
      (is (= "open" (:name (first (get-in (sut/handle-manifest-request ctx {:method "tools/list" :id 2}
                                                                         {:settings {"config.on" false}})
                                        [:result :tools]))))))))

(deftest resources-list-test
  (if-let [extension-path (test-fixture-extension-path)]
    (let [ctx (mock-skill-context extension-path)]
      (testing "lists skills without skill-path"
        (let [resources (get-in (sut/handle-manifest-request ctx {:method "resources/list" :id 1} {:settings {}})
                                [:result :resources])]
          (is (= 1 (count resources)))
          (is (= "skill://test-skill" (:uri (first resources))))
          (is (nil? (:skill-path (first resources)))))))
    (js/console.warn "Skipping resources-list-test: fixture not found")))

(deftest resources-read-test
  (if-let [extension-path (test-fixture-extension-path)]
    (let [ctx (mock-skill-context extension-path)]
      (testing "reads skill via manifest"
        (let [res (sut/handle-manifest-request ctx {:method "resources/read" :id 1 :params {:uri "skill://test-skill"}}
                                                 {:settings {}})]
          (is (string/includes? (get-in res [:result :contents 0 :text]) "Test Skill"))))

      (testing "hook wins when it returns content"
        (let [res (sut/handle-manifest-request ctx {:method "resources/read" :id 2 :params {:uri "skill://test-skill"}}
                                                 {:settings {}
                                                  :read-resource+ (fn [_ uri _]
                                                                    {:contents [{:uri uri :text "custom"}]})})]
          (is (= "custom" (get-in res [:result :contents 0 :text])))))

      (testing "hook nil falls through to manifest"
        (let [res (sut/handle-manifest-request ctx {:method "resources/read" :id 3 :params {:uri "skill://test-skill"}}
                                                 {:settings {}
                                                  :read-resource+ (fn [_ _ _] nil)})]
          (is (string/includes? (get-in res [:result :contents 0 :text]) "Test Skill"))))

      (testing "async hook Promise"
        (async done
          (-> (sut/handle-manifest-request ctx {:method "resources/read" :id 4 :params {:uri "custom://x"}}
                                             {:settings {}
                                              :read-resource+ (fn [_ uri _]
                                                                (p/resolved {:contents [{:uri uri :text "async"}]}))})
              (p/then (fn [res]
                        (is (= "async" (get-in res [:result :contents 0 :text])))
                        (done)))))))
    (js/console.warn "Skipping resources-read-test: fixture not found")))

(deftest resource-templates-test
  (testing "empty by default"
    (let [ctx (mock-tool-context #js [])
          res (sut/handle-manifest-request ctx {:method "resources/templates/list" :id 1} {:settings {}})]
      (is (= [] (get-in res [:result :resourceTemplates])))))

  (testing "hook provides templates"
    (let [ctx (mock-tool-context #js [])
          tpl [{:uriTemplate "/foo/{bar}" :name "foo"}]
          res (sut/handle-manifest-request ctx {:method "resources/templates/list" :id 2}
                                           {:settings {}
                                            :resource-templates+ (fn [_ _] tpl)})]
      (is (= tpl (get-in res [:result :resourceTemplates]))))))

(deftest ping-test
  (is (= {} (get-in (sut/handle-manifest-request #js {} {:method "ping" :id 1} {:settings {}})
                    [:result]))))

(deftest unknown-method-test
  (let [res (sut/handle-manifest-request #js {} {:method "nope" :id 1} {:settings {}})]
    (is (= -32601 (get-in res [:error :code])))
    (is (string/includes? (get-in res [:error :message]) "nope")))
  (is (nil? (sut/handle-manifest-request #js {} {:method "nope"} {:settings {}}))))
