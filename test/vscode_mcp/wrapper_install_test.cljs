(ns vscode-mcp.wrapper-install-test
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.wrapper-install :as sut]))

(deftest installed-path-test
  (testing "joins install dir with script basename"
    (is (= "/home/x/.config/ext/foo-mcp-server.js"
           (sut/installed-path "/home/x/.config/ext" "dist/foo-mcp-server.js")))))

(deftest ensure-installed-symlink-in-debug-test
  (testing "DEBUG install creates symlink under install dir"
    (let [tmp-root (fs/mkdtempSync (path/join (os/tmpdir) "vscode-mcp-wrapper-"))
          ext-dir (path/join tmp-root "ext")
          dist-dir (path/join ext-dir "dist")
          install-dir (path/join tmp-root "install")
          source-path (path/join dist-dir "foo-mcp-server.js")
          source-content "console.log('wrapper');\n"
          fake-ctx #js {:extensionPath ext-dir}]
      (try
        (fs/mkdirSync dist-dir #js {:recursive true})
        (fs/writeFileSync source-path source-content)
        (let [dest (sut/ensure-installed!
                    {:vscode/extension-context fake-ctx
                     :cursor/script-relative-path "dist/foo-mcp-server.js"
                     :lifecycle/wrapper-install-dir install-dir})]
          (is (= (sut/installed-path install-dir "dist/foo-mcp-server.js") dest))
          (is (fs/existsSync dest))
          (is (.isSymbolicLink (fs/lstatSync dest)))
          (is (= source-content (fs/readFileSync dest "utf8")))
          (is (= (fs/realpathSync source-path) (fs/realpathSync dest))))
        (finally
          (fs/rmSync tmp-root #js {:recursive true :force true}))))))
