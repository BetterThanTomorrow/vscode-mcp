(ns vscode-mcp.manual-setup-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.manual-setup :as sut]))

(deftest copy-command-strings-test
  (let [wrapper "/ext/dist/mcp-server.js"
        server-info {:server/assigned-port 1664
                     :server/host "127.0.0.1"
                     :server/port-file-uri #js {:fsPath "/ws/.calva/mcp-server/port"}}
        commands (sut/copy-command-strings wrapper server-info)]
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
  (testing "no port-note, no suffix"
    (is (= " MCP socket server started on port: 1664"
           (sut/format-start-message {:server/assigned-port 1664}))))

  (testing "port-note prefix included"
    (is (= "NOTE: Port 1664 was already in use. MCP socket server started on port: 54321"
           (sut/format-start-message {:server/assigned-port 54321
                                      :server/port-note "NOTE: Port 1664 was already in use."}))))

  (testing "message-suffix appended"
    (is (= " MCP socket server started on port: 1664\n\nSee the README for setup details."
           (sut/format-start-message {:server/assigned-port 1664}
                                     {:manual-setup/message-suffix "See the README for setup details."}))))

  (testing "blank suffix is not appended"
    (is (= " MCP socket server started on port: 1664"
           (sut/format-start-message {:server/assigned-port 1664}
                                     {:manual-setup/message-suffix ""})))))
