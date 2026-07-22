(ns vscode-mcp.jsonc-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [vscode-mcp.jsonc :as sut]))

(def sample-jsonc
  (str "{\n"
       "  // keep me\n"
       "  \"providers\": {\n"
       "    \"openrouter\": {\"key\": \"secret\"}\n"
       "  },\n"
       "  \"$schema\": \"https://eca.dev/config.json\"\n"
       "}\n"))

(def joyride-entry
  {:command "node"
   :args ["/ext/dist/joyride-mcp-server.js"
          "/ws/.joyride/mcp-server/port"
          "127.0.0.1"]})

(def joyride-entry'
  (assoc joyride-entry
         :args ["/ext/dist/joyride-mcp-server.js"
                "/ws/.joyride/mcp-server/port"
                "0.0.0.0"]))

(deftest parse-jsonc-with-comments-test
  (testing "comments are accepted"
    (is (= {:providers {:openrouter {:key "secret"}}
            :$schema "https://eca.dev/config.json"}
           (sut/parse-clj sample-jsonc)))))

(deftest assoc-in-text-preserves-comments-and-siblings-test
  (let [next (sut/assoc-in-text sample-jsonc ["mcpServers" "joyride"] joyride-entry)
        data (sut/parse-clj next)]
    (testing "comment and unrelated keys survive"
      (is (str/includes? next "// keep me"))
      (is (= "secret" (get-in data [:providers :openrouter :key])))
      (is (= "https://eca.dev/config.json" (:$schema data))))
    (testing "new entry is present"
      (is (= joyride-entry (get-in data [:mcpServers :joyride]))))))

(deftest assoc-in-text-updates-existing-entry-test
  (let [with-entry (sut/assoc-in-text sample-jsonc ["mcpServers" "joyride"] joyride-entry)
        updated (sut/assoc-in-text with-entry ["mcpServers" "joyride"] joyride-entry')
        data (sut/parse-clj updated)]
    (is (str/includes? updated "// keep me"))
    (is (= joyride-entry' (get-in data [:mcpServers :joyride])))
    (is (= "0.0.0.0" (last (get-in data [:mcpServers :joyride :args]))))))

(deftest assoc-in-text-blank-document-test
  (is (= joyride-entry
         (get-in (sut/parse-clj (sut/assoc-in-text nil ["mcpServers" "joyride"] joyride-entry))
                 [:mcpServers :joyride])))
  (is (= joyride-entry
         (get-in (sut/parse-clj (sut/assoc-in-text "" ["mcpServers" "joyride"] joyride-entry))
                 [:mcpServers :joyride]))))

(deftest idempotent-semantic-roundtrip-test
  (testing "setting the same value yields equal parsed data"
    (let [once (sut/assoc-in-text sample-jsonc ["mcpServers" "joyride"] joyride-entry)
          twice (sut/assoc-in-text once ["mcpServers" "joyride"] joyride-entry)]
      (is (= (sut/parse-clj once) (sut/parse-clj twice)))
      (is (= joyride-entry (get-in (sut/parse-clj twice) [:mcpServers :joyride])))))

  (testing "caller can skip write when entry already matches"
    (let [configured (sut/assoc-in-text sample-jsonc ["mcpServers" "joyride"] joyride-entry)
          current (get-in (sut/parse-clj configured) [:mcpServers :joyride])]
      (is (= joyride-entry current)
          "compare desired vs current before writing"))))
