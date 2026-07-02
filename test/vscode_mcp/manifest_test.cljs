(ns vscode-mcp.manifest-test
  (:require
   ["fs" :as fs]
   ["path" :as path]
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
  (testing "returns nil for empty inputs"
    (is (nil? (sut/build-server-instructions {})) "returns nil when no inputs provided"))

  (testing "handles only base text"
    (is (= "Just base text" (sut/build-server-instructions {:base-text "Just base text"})) "returns base text alone"))

  (testing "handles tools"
    (let [tools [{:name "my-tool" :description "Tool description"}]]
      (is (= "Available tools:\n- **`my-tool`**: Tool description"
             (sut/build-server-instructions {:tools tools}))
          "formats tools block correctly")))

  (testing "handles resources"
    (let [resources [{:name "my-skill" :description "Skill description"}]]
      (is (= "Specialized skills are available as resources. Use `resources/list` to discover them and `resources/read` to load their full instructions before starting work in their domain:\n- **my-skill**: Skill description"
             (sut/build-server-instructions {:resources resources}))
          "formats resources block correctly")))

  (testing "handles everything combined"
    (let [tools [{:name "my-tool" :description "Tool description"}]
          resources [{:name "my-skill" :description "Skill description"}]]
      (is (= "Base text\n\nAvailable tools:\n- **`my-tool`**: Tool description\n\nSpecialized skills are available as resources. Use `resources/list` to discover them and `resources/read` to load their full instructions before starting work in their domain:\n- **my-skill**: Skill description"
             (sut/build-server-instructions {:base-text "Base text"
                                             :tools tools
                                             :resources resources}))
          "combines base-text, tools, and resources with double newlines"))))

(deftest find-skill-resource-by-uri-test
  (let [resources [{:uri "skill://test-skill" :name "test-skill"}]]
    (testing "matches canonical URI"
      (is (= (first resources)
             (sut/find-skill-resource-by-uri resources "skill://test-skill"))))

    (testing "matches skill://{name}/SKILL.md alias"
      (is (= (first resources)
             (sut/find-skill-resource-by-uri resources "skill://test-skill/SKILL.md"))))

    (testing "returns nil for unknown URI"
      (is (nil? (sut/find-skill-resource-by-uri resources "skill://missing"))))))

(deftest read-resource-test
  (if-let [extension-path (test-fixture-extension-path)]
    (let [ctx (mock-skill-context extension-path)]
      (testing "reads canonical skill URI"
        (let [result (sut/read-resource ctx "skill://test-skill")]
          (is (= "skill://test-skill" (:uri result)))
          (is (string/includes? (:text result) "Test Skill"))))

      (testing "reads skill://{name}/SKILL.md alias and echoes requested URI"
        (let [result (sut/read-resource ctx "skill://test-skill/SKILL.md")]
          (is (= "skill://test-skill/SKILL.md" (:uri result)))
          (is (string/includes? (:text result) "Test Skill"))))

      (testing "returns nil for unknown URI"
        (is (nil? (sut/read-resource ctx "skill://missing"))))

      (testing "get-resources lists canonical URI only"
        (let [resources (sut/get-resources ctx)]
          (is (= 1 (count resources)))
          (is (= "skill://test-skill" (:uri (first resources)))))))
    (js/console.warn "Skipping read-resource-test: fixture not found")))
