(ns vscode-mcp.cursor-config
  "Internal to `vscode-mcp.cursor` — not part of the consumer API.

   Pure config-building helpers with no `\"vscode\"` require, kept separate so
   they can be unit-tested directly."
  (:require
   ["path" :as path]
   [vscode-mcp.stdio-config :as stdio-config]))

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

(defn- normalize-registration-config
  "Keywordizes keys and canonicalizes shape so workspaceState JSON round-trip
   compares equal to the CLJ original."
  [config]
  (when config
    (let [kw (js->clj (clj->js config) :keywordize-keys true)
          server (:server kw)]
      {:name (:name kw)
       :server (when server
                 {:command (:command server)
                  :args (vec (or (:args server) []))
                  :env (or (:env server) {})})})))

(defn registration-config-changed?
  "True when `stored-config` differs from `fresh-config` over :name and server
   {:command :args :env}, or when nothing was stored yet (first registration)."
  [stored-config fresh-config]
  (not= (normalize-registration-config stored-config)
        (normalize-registration-config fresh-config)))
