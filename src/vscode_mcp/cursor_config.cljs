(ns vscode-mcp.cursor-config
  (:require
   ["path" :as path]))

(defn mcp-client-identifier
  "Cursor MCP service identifier for `mcp.reloadClient`.
   Built as user-{extensionId}-extension-{registerServer name}"
  [{:vscode/keys [^js extension-context] :cursor/keys [server-name]}]
  (when extension-context
    (let [extension-id (some-> extension-context .-extension .-id)]
      (when (seq extension-id)
        (str "user-" extension-id "-extension-" server-name)))))

(defn wrapper-script-path
  "Absolute path to the stdio wrapper bundled with the running extension instance."
  [{:vscode/keys [^js extension-context] :cursor/keys [script-relative-path]}]
  (when extension-context
    (path/join (.-extensionPath extension-context) script-relative-path)))

(defn build-stdio-server-config
  [{:cursor/keys [server-name wrapper-path] :server/keys [port-file-path host]}]
  (cond
    (not (seq (str wrapper-path)))
    {:ok false :reason :missing-wrapper-path}

    (not (seq (str port-file-path)))
    {:ok false :reason :missing-port-file-path}

    (not (seq (str host)))
    {:ok false :reason :missing-host}

    :else
    {:ok true
     :config {:name server-name
              :server {:command "node"
                       :args [(str wrapper-path) (str port-file-path) (str host)]
                       :env {}}}}))

(defn build-cursor-mcp-registration-config
  [{:cursor/keys [server-name script-relative-path]
    :vscode/keys [extension-context]
    :server/keys [port-file-uri host]}]
  (let [port-file-fs-path (some-> port-file-uri (unchecked-get "fsPath"))]
    (build-stdio-server-config
     {:cursor/server-name server-name
      :cursor/wrapper-path (wrapper-script-path {:vscode/extension-context extension-context
                                                  :cursor/script-relative-path script-relative-path})
      :server/port-file-path port-file-fs-path
      :server/host host})))
