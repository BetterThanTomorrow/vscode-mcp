(ns vscode-mcp.port-file-test
  (:require
   ["fs" :as fs]
   ["net" :as net]
   ["os" :as os]
   ["path" :as path]
   [cljs.test :refer [async deftest is testing]]
   [promesa.core :as p]
   [vscode-mcp.port-file :as sut]))

(defn- tmp-port-dir []
  (fs/mkdtempSync (path/join (os/tmpdir) "vscode-mcp-port-files-")))

(deftest path-formula-test
  (testing "default dir under ~/.config/vscode-mcp/port-files"
    (is (= (path/join (os/homedir) ".config" "vscode-mcp" "port-files")
           (sut/default-dir))))

  (testing "filename is serverName-windowId.port"
    (is (= "calva-backseat-driver-ws-abc.port"
           (sut/filename "calva-backseat-driver" "ws-abc"))))

  (testing "primary-path joins dir and filename"
    (is (= (path/join "/tmp/ports" "joyride-ws-1.port")
           (sut/primary-path {:port-file/dir "/tmp/ports"} "joyride" "ws-1")))
    (is (= (path/join (sut/default-dir) "joyride-ws-1.port")
           (sut/primary-path "joyride" "ws-1"))))

  (testing ":port-file/dir override wins"
    (is (= "/custom" (sut/port-dir {:port-file/dir "/custom"})))))

(deftest create-delete-invariant-test
  (testing "primary path exists after write and is gone after delete"
    (let [dir (tmp-port-dir)
          file-path (sut/primary-path {:port-file/dir dir} "svc" "ws-x")]
      (fs/mkdirSync dir #js {:recursive true})
      (fs/writeFileSync file-path "1664")
      (is (fs/existsSync file-path))
      (is (= "1664" (str (fs/readFileSync file-path "utf8"))))
      (fs/unlinkSync file-path)
      (is (not (fs/existsSync file-path)))
      (fs/rmSync dir #js {:recursive true :force true}))))

(deftest sweep-stale-test
  (testing "missing dir is zero"
    (async done
           (p/let [n (sut/sweep-stale!+ "/tmp/vscode-mcp-port-files-missing-xyz")]
             (is (= 0 n))
             (done))))

  (testing "invalid content is deleted"
    (async done
           (let [dir (tmp-port-dir)
                 bad (path/join dir "svc-ws-bad.port")]
             (fs/writeFileSync bad "not-a-port")
             (p/let [n (sut/sweep-stale!+ dir)]
               (is (= 1 n))
               (is (not (fs/existsSync bad)))
               (fs/rmSync dir #js {:recursive true :force true})
               (done)))))

  (testing "non-listening port file is deleted; listening port file is kept"
    (async done
           (let [dir (tmp-port-dir)
                 server (net/createServer)
                 dead (path/join dir "svc-ws-dead.port")
                 live (path/join dir "svc-ws-live.port")]
             (.listen server 0 "127.0.0.1"
                      (fn []
                        (let [port (.-port (.address server))]
                          (fs/writeFileSync dead "1")
                          (fs/writeFileSync live (str port))
                          (p/let [n (sut/sweep-stale!+ dir "127.0.0.1")]
                            (is (= 1 n))
                            (is (not (fs/existsSync dead)))
                            (is (fs/existsSync live))
                            (.close server)
                            (fs/rmSync dir #js {:recursive true :force true})
                            (done)))))))))
