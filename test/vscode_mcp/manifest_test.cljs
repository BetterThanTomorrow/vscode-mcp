(ns vscode-mcp.manifest-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.manifest :as sut]))

(deftest satisfies-when?-test
  (testing "empty when-clause satisfies"
    (is (true? (sut/satisfies-when? "" {})))
    (is (true? (sut/satisfies-when? nil {}))))

  (testing "matches key in settings"
    (is (true? (sut/satisfies-when? "config.someSetting" {"config.someSetting" true})))
    (is (false? (sut/satisfies-when? "config.someSetting" {"config.someSetting" false}))))

  (testing "defaults to true if setting is missing"
    (is (true? (sut/satisfies-when? "config.missingSetting" {})))))

(deftest read-skill-frontmatter-test
  (testing "parses valid frontmatter"
    (let [content "---\nname: my-skill\ndescription: A great skill\n---\n\n# Body here"]
      (is (= {:name "my-skill"
              :description "A great skill"}
             (sut/read-skill-frontmatter content)))))

  (testing "handles missing fields"
    (let [content "---\nname: my-skill\n---\nBody"]
      (is (= {:name "my-skill"
              :description nil}
             (sut/read-skill-frontmatter content)))))

  (testing "preserves quotes in values"
    (let [content "---\nname: 'quoted-skill'\ndescription: \"Quoted desc\"\n---\nBody"]
      (is (= {:name "'quoted-skill'"
              :description "\"Quoted desc\""}
             (sut/read-skill-frontmatter content)))))

  (testing "returns nil if no frontmatter"
    (is (nil? (sut/read-skill-frontmatter "# Just a body"))))

  (testing "handles multi-line values"
    (let [content "---\nname: my-skill\ndescription: This is a\n multi-line\n description.\n---\nBody"]
      (is (= {:name "my-skill"
              :description "This is a\n multi-line\n description."}
             (sut/read-skill-frontmatter content)))))

  (testing "handles multi-line values containing colons"
    (let [content "---\nname: joyride\ndescription: >-\n  Joyride core\n  Use when: working with things.\n---\nBody"]
      (is (= {:name "joyride"
              :description ">-\n  Joyride core\n  Use when: working with things."}
             (sut/read-skill-frontmatter content))))))
