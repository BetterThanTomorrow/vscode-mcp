(ns vscode-mcp.manual-setup-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as string]
   [vscode-mcp.manual-setup :as sut]))

(deftest copy-command-strings-test
  (let [wrapper "/ext/dist/mcp-server.js"
        server-info {:server/assigned-port 1664
                     :server/host "127.0.0.1"
                     :server/port-file-uri #js {:fsPath "/ws/.calva/mcp-server/port"}}
        commands (sut/copy-command-strings wrapper server-info)]
    (is (= "node /ext/dist/mcp-server.js" (:manual-setup/caller-window commands))
        "The caller's-window command names no port, port file or host")
    (testing "port variant uses host-aware stdio-config format"
      (is (= "node /ext/dist/mcp-server.js 1664 127.0.0.1"
             (:manual-setup/port commands))))

    (testing "port-file variant uses host-aware stdio-config format"
      (is (= "node /ext/dist/mcp-server.js /ws/.calva/mcp-server/port 127.0.0.1"
             (:manual-setup/port-file commands)))))

  (testing "custom host preserved in both variants"
    (let [commands (sut/copy-command-strings "/ext/mcp.js"
                                             {:server/assigned-port 1664
                                              :server/host "0.0.0.0"
                                              :server/port-file-uri #js {:fsPath "/ws/port"}})]
      (is (= "node /ext/mcp.js 1664 0.0.0.0" (:manual-setup/port commands)))
      (is (= "node /ext/mcp.js /ws/port 0.0.0.0" (:manual-setup/port-file commands))))))

(deftest format-start-message-test
  (is (= (str "Joyride:  MCP socket server started on port: 1664. " sut/two-ways-text)
         (sut/format-start-message {:server/assigned-port 1664}
                                   {:manual-setup/extension-name "Joyride"}))
      "The message names the port and the two ways to run the server")
  (is (string/ends-with? (sut/format-start-message {:server/assigned-port 1664}
                                                   {:manual-setup/extension-name "Joyride"
                                                    :manual-setup/message-suffix "More."})
                         (str sut/two-ways-text "\n\nMore."))
      "A product suffix comes after"))
