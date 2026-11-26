(ns vscode-mcp.wrapper-axs-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [vscode-mcp.stdio.wrapper-axs :as sut]))

(def ^:private script-name "calva-mcp-server.js")

(def ^:private initialize "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")
(def ^:private initialized "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
(def ^:private tools-list "{\"jsonrpc\":\"2.0\",\"id\":\"b\",\"method\":\"tools/list\"}")

(defn- handle
  "Returns the state after `actions`, handled in order from `state`, and the effects of
   the last one."
  [state & actions]
  (reduce (fn [{:uf/keys [db]} action]
            (sut/handle-action db action))
          {:uf/db state}
          actions))

(defn- without-logs
  "The effects of `result` that aren't log lines."
  [result]
  (vec (remove #(= :log/fx.write (first %)) (:uf/fxs result))))

(defn- started
  "The result of starting the wrapper with a port file at time 0."
  []
  (sut/handle-action nil [:wrapper/ax.start {:wrapper/script-name script-name
                                             :connect/port-or-port-file "/tmp/the.port"
                                             :connect/host "127.0.0.1"
                                             :connect/now-ms 0}]))

(def ^:private live-entry
  "The entry of the wrapper's own server in the window that `VSCODE_MCP_WINDOW_ID` names."
  {:windowId "ws-abc"
   :serverName "calva-backseat-driver"
   :pid 4711
   :mcp {:portFilePath "/home/.config/vscode-mcp/port-files/calva-backseat-driver-ws-abc.port"
         :host "127.0.0.1"
         :wrapperPath "/home/.config/calva/backseat-driver/calva-mcp-server.js"}})

(def ^:private window-search {:caller/env-window-id "ws-abc"})

(defn- started-for-the-callers-window
  "The result of starting the wrapper without a port, with `entries` in the registry."
  [entries]
  (sut/handle-action nil [:wrapper/ax.start {:wrapper/script-name script-name
                                             :connect/search window-search
                                             :connect/entries entries
                                             :connect/now-ms 0}]))

(deftest start
  (testing "with a port file"
    (let [result (started)]
      (is (= :waiting (get-in result [:uf/db :wrapper/phase])))
      (is (= [[:connect/fx.read-port-file "/tmp/the.port" "127.0.0.1"]]
             (without-logs result))
          "The first attempt reads the port file")
      (is (= {:connect/timeout-ms 60000 :connect/interval-ms 500}
             (get-in result [:uf/db :connect/options])))))
  (testing "with a port"
    (is (= [[:connect/fx.connect 1664 "127.0.0.1"]]
           (without-logs (sut/handle-action nil [:wrapper/ax.start {:wrapper/script-name script-name
                                                                    :connect/port-or-port-file "1664"
                                                                    :connect/host "0.0.0.0"
                                                                    :connect/now-ms 0}])))
        "The first attempt connects to the port, on loopback for an unspecified address"))
  (testing "without a port, with the caller's window in the registry"
    (let [result (started-for-the-callers-window [live-entry])]
      (is (= [[:connect/fx.read-entries window-search script-name]]
             (without-logs result))
          "The first attempt reads the registry's entries")
      (is (= 60000 (get-in result [:uf/db :connect/options :connect/timeout-ms]))
          "and the wrapper waits as long as for a port file")
      (is (= [:log/fx.write :info "Looking for the caller's window:" "the window VSCODE_MCP_WINDOW_ID names, ws-abc"]
             (first (:uf/fxs result)))
          "It logs what it looks for")))
  (testing "without a port, when no window can come"
    (is (= sut/settled-timeout-ms
           (get-in (started-for-the-callers-window []) [:uf/db :connect/options :connect/timeout-ms]))
        "The wrapper waits only long enough to answer the client's first request")))

(deftest stdin-while-waiting
  (let [result (handle (:uf/db (started))
                       [:stdin/ax.data (str initialize "\n" (subs initialized 0 10))]
                       [:stdin/ax.data (str (subs initialized 10) "\n\n  \n")])]
    (is (= [initialize initialized]
           (get-in result [:uf/db :wrapper/queued-lines]))
        "Complete lines are queued, also one that came in two chunks, and blank lines are left out")
    (is (= [] (:uf/fxs result))
        "Nothing is sent while the wrapper waits")))

(deftest stdin-ends-while-waiting
  (let [result (handle (:uf/db (started))
                       [:stdin/ax.data (str initialize "\n")]
                       [:stdin/ax.ended])]
    (is (= [[:connect/fx.cancel]
            [:process/fx.exit 0]]
           (:uf/fxs result))
        "The wrapper stops connecting and exits cleanly, and the queued request gets no answer")
    (is (= :ended (get-in result [:uf/db :wrapper/phase])))
    (is (= []
           (:uf/fxs (handle (:uf/db result)
                            [:connect/ax.port-read {:connect/port 1664 :connect/host "127.0.0.1" :connect/now-ms 10}])))
        "A port that is read after that isn't connected to")
    (is (= [[:connect/fx.cancel]]
           (:uf/fxs (handle (:uf/db result)
                            [:connect/ax.connected {:connect/port 1664 :connect/host "127.0.0.1"}])))
        "and a connect that comes after that is closed")))

(deftest a-failed-attempt
  (testing "within the connect budget"
    (is (= [[:connect/fx.retry-after 500]]
           (without-logs (handle (:uf/db (started))
                                 [:connect/ax.port-read {:connect/port nil :connect/now-ms 59999}])))
        "A port file without a port is tried again after the interval")
    (is (= [[:connect/fx.retry-after 500]]
           (without-logs (handle (:uf/db (started))
                                 [:connect/ax.failed {:connect/now-ms 1000 :connect/reason "ECONNREFUSED"}])))
        "and so is a connect that was refused")
    (is (= [[:connect/fx.read-port-file "/tmp/the.port" "127.0.0.1"]]
           (without-logs (handle (:uf/db (started)) [:connect/ax.attempt])))
        "The next attempt reads the port file again")))

(deftest giving-up-with-requests-queued
  (let [result (handle (:uf/db (started))
                       [:stdin/ax.data (str initialize "\n" initialized "\n" tools-list "\n")]
                       [:connect/ax.failed {:connect/now-ms 60000 :connect/reason "port unavailable"}])
        message sut/port-file-give-up-message]
    (is (= [[:connect/fx.cancel]
            [:stdout/fx.write (str "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":" (js/JSON.stringify message) "},\"id\":1}")]
            [:stdout/fx.write (str "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":" (js/JSON.stringify message) "},\"id\":\"b\"}")]
            [:process/fx.exit 1]]
           (without-logs result))
        "Each queued request gets an error, the notification gets none, and the wrapper exits with an error")
    (is (some #{[:log/fx.write :error message]} (:uf/fxs result))
        "The message is logged as an error")
    (is (= :gave-up (get-in result [:uf/db :wrapper/phase])))
    (is (= []
           (:uf/fxs (handle (:uf/db result) [:connect/ax.attempt])))
        "An attempt that was scheduled before does nothing")))

(deftest giving-up-with-nothing-queued
  (is (= [[:connect/fx.cancel]
          [:stdout/fx.write (str "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":"
                                 (js/JSON.stringify sut/port-file-give-up-message)
                                 "}}")]
          [:process/fx.exit 1]]
         (without-logs (handle (:uf/db (started))
                               [:connect/ax.failed {:connect/now-ms 61000 :connect/reason "port unavailable"}])))
      "One error without an id goes out"))

(deftest the-callers-window
  (testing "its entry is there"
    (is (= [[:connect/fx.read-port-file (get-in live-entry [:mcp :portFilePath]) "127.0.0.1"]]
           (without-logs (handle (:uf/db (started-for-the-callers-window [live-entry]))
                                 [:connect/ax.entries-read {:connect/entries [live-entry] :connect/now-ms 10}])))
        "The port file of the wrapper's own server in that window is read"))
  (testing "a window that appears on the last attempt"
    (let [result (handle (:uf/db (started-for-the-callers-window [live-entry]))
                         [:stdin/ax.data (str initialize "\n")]
                         [:connect/ax.entries-read {:connect/entries [] :connect/now-ms 59500}]
                         [:connect/ax.attempt]
                         [:connect/ax.entries-read {:connect/entries [live-entry] :connect/now-ms 59999}]
                         [:connect/ax.port-read {:connect/port 49311 :connect/host "127.0.0.1" :connect/now-ms 60001}]
                         [:connect/ax.connected {:connect/port 49311 :connect/host "127.0.0.1"}])]
      (is (= :connected (get-in result [:uf/db :wrapper/phase])))
      (is (= [[:socket/fx.relay]
              [:socket/fx.write initialize]]
             (without-logs result))
          "The wrapper relays, starting with the request that was queued")
      (is (= [] (get-in result [:uf/db :wrapper/queued-lines])))))
  (testing "no window in time"
    (let [result (handle (:uf/db (started-for-the-callers-window []))
                         [:stdin/ax.data (str initialize "\n")]
                         [:connect/ax.entries-read {:connect/entries [] :connect/now-ms sut/settled-timeout-ms}])
          [_cancel [_ line] exit] (without-logs result)
          response (js->clj (js/JSON.parse line) :keywordize-keys true)]
      (is (= 1 (:id response))
          "The queued request is answered")
      (is (= -32001 (get-in response [:error :code])))
      (is (re-find #"^No window found for calva-mcp-server.js\. Looked for the window VSCODE_MCP_WINDOW_ID names, ws-abc"
                   (get-in response [:error :message]))
          "with what was looked for")
      (is (= [:process/fx.exit 1] exit)))))

(deftest relaying
  (let [connected (:uf/db (handle (:uf/db (started))
                                  [:connect/ax.connected {:connect/port 1664 :connect/host "127.0.0.1"}]))]
    (testing "from stdin to the socket"
      (is (= [[:socket/fx.write initialize]
              [:socket/fx.write tools-list]]
             (without-logs (handle connected [:stdin/ax.data (str initialize "\n" tools-list "\n")])))
          "Each complete line is sent")
      (is (= [[:socket/fx.write initialize]]
             (without-logs (handle connected
                                   [:stdin/ax.data initialize]
                                   [:stdin/ax.ended])))
          "A last line without a newline is sent when stdin ends, and the wrapper stays connected"))
    (testing "from the socket to stdout"
      (is (= [[:stdout/fx.write "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"]]
             (without-logs (handle connected
                                   [:socket/ax.data "{\"jsonrpc\":\"2.0\","]
                                   [:socket/ax.data "\"id\":1,\"result\":{}}\nnot json\n"])))
          "A message that came in two chunks goes out as one line, and a line that isn't JSON is left out")
      (is (= [[:stdout/fx.write "[1]"]]
             (without-logs (handle connected
                                   [:socket/ax.data "[1]"]
                                   [:socket/ax.ended])))
          "A last message without a newline goes out when the socket's stream ends"))
    (testing "the connection breaking"
      (is (= [[:stdout/fx.write "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"Server connection error: read ECONNRESET\"}}"]
              [:process/fx.exit 1]]
             (without-logs (handle connected [:socket/ax.error "read ECONNRESET"])))
          "A socket error gives the client an error, and the wrapper exits with one")
      (is (= [[:process/fx.exit 0]]
             (without-logs (handle connected [:socket/ax.closed false])))
          "A clean close ends the wrapper cleanly")
      (is (= [[:process/fx.exit 1]]
             (without-logs (handle connected [:socket/ax.closed true])))
          "and a close from an error ends it with an error"))))

(deftest an-unknown-action
  (let [state (:uf/db (started))]
    (is (= {:uf/db state
            :uf/fxs [[:log/fx.write :error "Unknown action:" ":nope/ax.nope"]]}
           (sut/handle-action state [:nope/ax.nope 1])))))
