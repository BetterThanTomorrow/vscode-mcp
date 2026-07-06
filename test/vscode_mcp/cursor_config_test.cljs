(ns vscode-mcp.cursor-config-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.cursor-config :as sut]))

(def sample-config
  {:name "test-server"
   :server {:command "node"
            :args ["/ext/dist/mcp-server.js" "/ws/.calva/mcp-server/port" "127.0.0.1"]
            :env {}}})

(deftest registration-config-changed?-test
  (testing "identical configs are not changed"
    (is (false? (sut/registration-config-changed? sample-config sample-config))))

  (testing "nil stored means first registration (changed)"
    (is (sut/registration-config-changed? nil sample-config)))

  (testing "changed wrapper path in :args is changed"
    (let [stored sample-config
          fresh (assoc-in sample-config [:server :args 0] "/other/wrapper.js")]
      (is (sut/registration-config-changed? stored fresh))))

  (testing "changed host in :args is changed"
    (let [stored sample-config
          fresh (assoc-in sample-config [:server :args 2] "0.0.0.0")]
      (is (sut/registration-config-changed? stored fresh))))

  (testing "JSON-round-tripped stored value compares equal to CLJ original"
    (let [stored (js->clj (clj->js sample-config))]
      (is (false? (sut/registration-config-changed? stored sample-config))))))

(deftest instance-slug-test
  (testing "workspace path yields deterministic ws- slug"
    (is (re-matches #"ws-[a-z0-9]+"
                    (sut/instance-slug #:instance{:workspace-root-path "/Users/dev/my project (v2)!"})))
    (is (= (sut/instance-slug #:instance{:workspace-root-path "/a/b"})
           (sut/instance-slug #:instance{:workspace-root-path "/a/b"}))
        "deterministic for the same path")
    (is (not= (sut/instance-slug #:instance{:workspace-root-path "/a/b"})
              (sut/instance-slug #:instance{:workspace-root-path "/c/b"}))
        "differs for different paths"))

  (testing "storage path yields win- slug"
    (is (re-matches #"win-[a-z0-9]+"
                    (sut/instance-slug #:instance{:storage-uri-path "/storage/abc"}))))

  (testing "no inputs yields random anon- slug"
    (is (re-matches #"anon-[0-9a-f]{8}" (sut/instance-slug {})))
    (is (not= (sut/instance-slug {})
              (sut/instance-slug {})))))

(deftest slugged-server-name-test
  (testing "suffixes the base name with the instance slug"
    (is (= "joyride-ws-2ypyqk"
           (sut/slugged-server-name "joyride" "ws-2ypyqk"))))

  (testing "generation 0 or nil leaves the 2-arity name unchanged"
    (is (= "joyride-ws-2ypyqk"
           (sut/slugged-server-name "joyride" "ws-2ypyqk" 0)))
    (is (= "joyride-ws-2ypyqk"
           (sut/slugged-server-name "joyride" "ws-2ypyqk" nil))))

  (testing "positive generation appends -g<generation>"
    (is (= "joyride-ws-2ypyqk-g2"
           (sut/slugged-server-name "joyride" "ws-2ypyqk" 2)))))
