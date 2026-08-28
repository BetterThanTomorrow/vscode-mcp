(ns vscode-mcp.cursor-config
  (:require
   ["path" :as path]
   [vscode-mcp.stdio-config :as stdio-config]))

(defn- short-hash [x]
  (.toString (js/Math.abs (hash x)) 36))

(defn app-id
  "Returns the editor CLI slug from product.json `applicationName`, or `uri-scheme`."
  [application-name uri-scheme]
  (or (not-empty application-name) (not-empty uri-scheme)))

(defn instance-slug
  "Returns `ws-<hash>` of `app-id` and the workspace file path, or the folder path if there is no workspace file.
   Without a folder: `win-<hash>` of `app-id` and the Extension Host pid."
  [{:instance/keys [app-id workspace-file-path workspace-root-path host-pid]}]
  (let [app (or app-id "")
        location (or (not-empty workspace-file-path)
                     (not-empty workspace-root-path))]
    (if location
      (str "ws-" (short-hash [app location]))
      (let [pid (or host-pid js/process.pid)]
        (str "win-" (short-hash [app pid]))))))

(defn mcp-client-identifier
  [{:vscode/keys [^js extension-context] :cursor/keys [server-name]}]
  (when extension-context
    (let [extension-id (some-> extension-context .-extension .-id)]
      (when (seq extension-id)
        (str "user-" extension-id "-extension-" server-name)))))

(defn slugged-server-name
  "Cursor registerServer name: `<base>-<slug>-g<generation>`.
   Generation always appears in the name — Cursor stalls when the same name is
   re-registered in one session after unregister."
  [base-name instance-slug generation]
  (str base-name "-" instance-slug "-g" generation))

(defn wrapper-script-path
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

    :else
    {:ok true
     :config {:name server-name
              :server {:command "node"
                       :args (stdio-config/stdio-args wrapper-path port-file-path host)
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
