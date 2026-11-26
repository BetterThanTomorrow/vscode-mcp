(ns vscode-mcp.manifest-test
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [cljs.reader :as reader]
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as string]
   [vscode-mcp.manifest :as sut]))

(deftest satisfies-when?-test
  (testing "empty when-clause satisfies"
    (is (true? (sut/satisfies-when? "" {})) "returns true for empty string")
    (is (true? (sut/satisfies-when? nil {})) "returns true for nil"))

  (testing "matches key in settings"
    (is (true? (sut/satisfies-when? "config.someSetting" {"config.someSetting" true})) "returns true when setting is true")
    (is (false? (sut/satisfies-when? "config.someSetting" {"config.someSetting" false})) "returns false when setting is false"))

  (testing "defaults to true if setting is missing"
    (is (true? (sut/satisfies-when? "config.missingSetting" {})) "returns true when setting is absent")))

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
       :extension #js {:packageJSON #js {:contributes #js {:chatSkills #js [#js {:path "stubs/skills/test-skill/SKILL.md"}]}}}})

(defn- mock-context [tools]
  #js {:extension #js {:packageJSON #js {:contributes #js {:languageModelTools tools}}}})

(deftest tool-call-allowed?-test
  (testing "no when clause"
    (let [ctx (mock-context #js [#js {:name "always-tool"}])]
      (is (= :allowed (sut/tool-call-allowed? ctx "always-tool")))))

  (testing "when setting true"
    (let [ctx (mock-context #js [#js {:name "gated-tool" :when "config.enabled"}])]
      (is (= :allowed (sut/tool-call-allowed? ctx "gated-tool" {:settings {"config.enabled" true}})))))

  (testing "when setting false"
    (let [ctx (mock-context #js [#js {:name "gated-tool" :when "config.enabled"}])]
      (is (= :disabled (sut/tool-call-allowed? ctx "gated-tool" {:settings {"config.enabled" false}})))))

  (testing "unknown tool"
    (let [ctx (mock-context #js [#js {:name "known-tool"}])]
      (is (= :unknown (sut/tool-call-allowed? ctx "missing-tool"))))))

(deftest read-skill-frontmatter-test
  (testing "parses valid frontmatter"
    (let [content "---\nname: my-skill\ndescription: A great skill\n---\n\n# Body here"]
      (is (= {:name "my-skill"
              :description "A great skill"}
             (sut/read-skill-frontmatter content))
          "returns parsed map of name and description")))

  (testing "parses CRLF frontmatter fields separately"
    (let [content "---\r\nname: backseat-driver\r\ndescription: Effective use of Backseat Driver\r\n---\r\nBody"]
      (is (= {:name "backseat-driver"
              :description "Effective use of Backseat Driver"}
             (sut/read-skill-frontmatter content))
          "does not fold the description line into the name")))

  (testing "handles missing fields"
    (let [content "---\nname: my-skill\n---\nBody"]
      (is (= {:name "my-skill"
              :description nil}
             (sut/read-skill-frontmatter content))
          "returns nil for missing description")))

  (testing "preserves quotes in values"
    (let [content "---\nname: 'quoted-skill'\ndescription: \"Quoted desc\"\n---\nBody"]
      (is (= {:name "'quoted-skill'"
              :description "\"Quoted desc\""}
             (sut/read-skill-frontmatter content))
          "returns values with quotes intact")))

  (testing "returns nil if no frontmatter"
    (is (nil? (sut/read-skill-frontmatter "# Just a body")) "returns nil when frontmatter block is absent"))

  (testing "handles multi-line values"
    (let [content "---\nname: my-skill\ndescription: This is a\n multi-line\n description.\n---\nBody"]
      (is (= {:name "my-skill"
              :description "This is a\n multi-line\n description."}
             (sut/read-skill-frontmatter content))
          "returns concatenated multi-line description")))

  (testing "handles multi-line values containing colons"
    (let [content "---\nname: joyride\ndescription: >-\n  Joyride core\n  Use when: working with things.\n---\nBody"]
      (is (= {:name "joyride"
              :description "Joyride core\n  Use when: working with things."}
             (sut/read-skill-frontmatter content))
          "does not split continuation lines on colons")))

  (testing "handles CRLF folded scalar marker"
    (let [content "---\r\nname: joyride\r\ndescription: >-\r\n  Joyride core\r\n  Use when: working with things.\r\n---\r\nBody"]
      (is (= {:name "joyride"
              :description "Joyride core\n  Use when: working with things."}
             (sut/read-skill-frontmatter content))
          "strips the folded scalar marker with CRLF line endings"))))

(deftest build-server-instructions-test
  (testing "catalog pointer when inputs are empty"
    (let [text (sut/build-server-instructions {})]
      (is (string? text) "returns a string")
      (is (string/includes? text "tools/list") "mentions tools/list")
      (is (string/includes? text "resources/list") "mentions resources/list")
      (is (string/includes? text "skill://index.json") "mentions skill://index.json")))

  (testing "handles only base text"
    (let [text (sut/build-server-instructions {:base-text "Just base text"})]
      (is (string/starts-with? text "Just base text") "preserves base text")
      (is (string/includes? text "tools/list") "still includes the catalog pointer")))

  (testing "handles tools"
    (let [tools [{:name "my-tool" :description "Tool description"}]
          text (sut/build-server-instructions {:tools tools})]
      (is (string/includes? text "tools/list") "includes the catalog pointer")
      (is (not (string/includes? text "Tool description")) "does not dump tool descriptions")
      (is (not (string/includes? text "my-tool")) "does not dump tool names")))

  (testing "handles resources"
    (let [resources [{:name "my-skill"
                      :uri "skill://my-skill/SKILL.md"
                      :description "Skill description"}]
          text (sut/build-server-instructions {:resources resources})]
      (is (string/includes? text "resources/list") "includes the catalog pointer")
      (is (string/includes? text "skill://index.json") "mentions the skills index")
      (is (not (string/includes? text "Skill description")) "does not dump skill descriptions")))

  (testing "handles everything combined"
    (let [tools [{:name "my-tool" :description "Tool description"}]
          resources [{:name "my-skill"
                      :uri "skill://my-skill/SKILL.md"
                      :description "Skill description"}]
          text (sut/build-server-instructions {:base-text "Base text"
                                               :tools tools
                                               :resources resources})]
      (is (string/starts-with? text "Base text") "preserves base text")
      (is (string/includes? text "tools/list") "includes the catalog pointer")
      (is (not (string/includes? text "Tool description")) "does not dump tool descriptions")
      (is (not (string/includes? text "Skill description")) "does not dump skill descriptions"))))

(deftest find-skill-resource-by-uri-test
  (let [resources [{:uri "skill://test-skill/SKILL.md" :name "test-skill"}]]
    (testing "matches canonical URI"
      (is (= (first resources)
             (sut/find-skill-resource-by-uri resources "skill://test-skill/SKILL.md"))))

    (testing "matches bare skill://{name} alias"
      (is (= (first resources)
             (sut/find-skill-resource-by-uri resources "skill://test-skill"))))

    (testing "returns nil for unknown URI"
      (is (nil? (sut/find-skill-resource-by-uri resources "skill://missing"))))))

(deftest read-resource-test
  (if-let [extension-path (test-fixture-extension-path)]
    (let [ctx (mock-skill-context extension-path)]
      (testing "reads canonical skill URI"
        (let [result (sut/read-resource ctx "skill://test-skill/SKILL.md")]
          (is (= "skill://test-skill/SKILL.md" (:uri result)))
          (is (string/includes? (:text result) "Test Skill"))))

      (testing "reads bare skill://{name} alias and echoes requested URI"
        (let [result (sut/read-resource ctx "skill://test-skill")]
          (is (= "skill://test-skill" (:uri result)))
          (is (string/includes? (:text result) "Test Skill"))))

      (testing "returns nil for unknown URI"
        (is (nil? (sut/read-resource ctx "skill://missing"))))

      (testing "get-resources lists canonical URI only"
        (let [resources (sut/get-resources ctx)]
          (is (= 1 (count resources)))
          (is (= "skill://test-skill/SKILL.md" (:uri (first resources))))))

      (testing "reads skills index JSON"
        (let [result (sut/read-resource ctx sut/skills-index-uri)
              parsed (js->clj (js/JSON.parse (:text result)) :keywordize-keys true)
              skill (first (:skills parsed))]
          (is (= sut/skills-index-uri (:uri result)))
          (is (= "application/json" (:mimeType result)))
          (is (= sut/skills-index-schema (:$schema parsed)))
          (is (= 1 (count (:skills parsed))))
          (is (= "test-skill" (:name skill)))
          (is (= "skill-md" (:type skill)))
          (is (= "skill://test-skill/SKILL.md" (:url skill)))))

      (testing "reads skill sibling file"
        (let [result (sut/read-resource ctx "skill://test-skill/references/example.md")]
          (is (= "skill://test-skill/references/example.md" (:uri result)))
          (is (= "text/markdown" (:mimeType result)))
          (is (string/includes? (:text result) "Sibling fixture for skill://test-skill/references/example.md"))))

      (testing "rejects path traversal for sibling reads"
        (is (nil? (sut/read-resource ctx "skill://test-skill/../SKILL.md")))
        (is (nil? (sut/read-resource ctx "skill://test-skill/references/../../SKILL.md"))))

      (testing "returns nil for missing sibling"
        (is (nil? (sut/read-resource ctx "skill://test-skill/references/missing.md")))))
    (js/console.warn "Skipping read-resource-test: fixture not found")))

(deftest get-tools-user-description-test
  (let [ctx (mock-context #js [#js {:name "t"
                                    :modelDescription "Model text"
                                    :userDescription "User text"
                                    :inputSchema #js {:type "object"
                                                      :properties #js {}
                                                      :required #js []}}])
        default-tool (first (sut/get-tools ctx))
        included-tool (first (sut/get-tools ctx {:includeUserDescription true}))]
    (testing "default payload has no userDescription"
      (is (= "t" (:name default-tool)))
      (is (= "Model text" (:description default-tool)))
      (is (not (contains? default-tool :userDescription))))
    (testing "includeUserDescription adds userDescription"
      (is (= "t" (:name included-tool)))
      (is (= "Model text" (:description included-tool)))
      (is (= "User text" (:userDescription included-tool))))))

(deftest manifest-as-data-test
  (let [extension-path (test-fixture-extension-path)
        context #js {:extensionPath extension-path
                     :extension #js {:packageJSON
                                     #js {:name "an-extension"
                                          :version "1.2.3"
                                          :contributes
                                          #js {:languageModelTools
                                               #js [#js {:name "always" :modelDescription "Always there"
                                                         :userDescription "For people"
                                                         :inputSchema #js {:type "object"
                                                                           :properties #js {:code #js {:type "string"}}
                                                                           :required #js ["code"]}}
                                                    #js {:name "sometimes" :modelDescription "By a setting"
                                                         :when "config.x.enabled"
                                                         :inputSchema #js {:type "object" :properties #js {}}}]
                                               :chatSkills #js [#js {:path "stubs/skills/test-skill/SKILL.md"}]}}}}
        data (sut/manifest-data context)
        settings {:settings {"config.x.enabled" false}}]
    (testing "the manifest as data is plain data"
      (is (= "an-extension" (:manifest/name data)))
      (is (= "1.2.3" (:manifest/version data)))
      (is (= ["always" "sometimes"] (mapv #(get % "name") (:manifest/tools data))))
      (is (= extension-path (:manifest/extension-path data)))
      (is (= data (reader/read-string (pr-str data)))
          "It prints and reads back as itself, so it holds no JavaScript objects"))
    (testing "every function gives the same for the data as for the extension context"
      (is (= (sut/get-tools context) (sut/get-tools data)))
      (is (= (sut/get-tools context settings) (sut/get-tools data settings)))
      (is (= (sut/get-tools context {:includeUserDescription true}) (sut/get-tools data {:includeUserDescription true})))
      (is (= [:allowed :disabled :unknown]
             (mapv #(sut/tool-call-allowed? context % settings) ["always" "sometimes" "nope"])
             (mapv #(sut/tool-call-allowed? data % settings) ["always" "sometimes" "nope"])))
      (is (= (sut/get-resources context) (sut/get-resources data)))
      (is (seq (sut/get-resources data)))
      (is (= (sut/read-resource context sut/skills-index-uri) (sut/read-resource data sut/skills-index-uri)))
      (is (= (sut/build-initialize-result context) (sut/build-initialize-result data)))
      (is (= {:name "an-extension" :version "1.2.3"}
             (:serverInfo (sut/build-initialize-result data)))))))
