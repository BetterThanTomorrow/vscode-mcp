(ns vscode-mcp.eca
  "Effectful ECA project-local MCP registration."
  (:require
   ["vscode" :as vscode]
   [promesa.core :as p]
   [vscode-mcp.cursor-config :as cursor-config]
   [vscode-mcp.eca-config :as eca-config]))

(def ^:private eca-extension-id "editor-code-assistant.eca")

(defn eca-available?
  "True when the ECA VS Code extension is installed."
  []
  (boolean (vscode/extensions.getExtension eca-extension-id)))

(defn workspace-root-present?
  "True when at least one workspace folder is open."
  []
  (boolean (some-> ^js (first vscode/workspace.workspaceFolders))))

(defn- workspace-root-uri []
  (some-> ^js (first vscode/workspace.workspaceFolders) .-uri))

(defn- read-text!+ [^js uri]
  (-> (vscode/workspace.fs.readFile uri)
      (p/then (fn [^js data]
                (.decode (js/TextDecoder.) data)))
      (p/catch (fn [_] nil))))

(defn- write-text!+ [^js uri text]
  (let [dir-uri (vscode/Uri.joinPath uri "..")
        data (.encode (js/TextEncoder.) text)]
    (-> (vscode/workspace.fs.createDirectory dir-uri)
        (p/catch (fn [_] nil))
        (p/then (fn [_] (vscode/workspace.fs.writeFile uri data)))
        (p/then (fn [_] {:ok true})))))

(defn register!+
  "Upsert the managed stdio MCP entry into project-local `.eca/config.json`.

   Never throws past the promise boundary. Activates the ECA extension before
   any read/write. Server key is `:cursor/server-name` (base name, not the
   generation-suffixed Cursor name). Wrapper path is extensionPath + script."
  [{:cursor/keys [server-name script-relative-path]
    :vscode/keys [extension-context]
    :server/keys [port-file-uri host]}]
  (-> (p/let [root-uri (workspace-root-uri)
              ^js ext (vscode/extensions.getExtension eca-extension-id)]
        (cond
          (not root-uri)
          {:ok false :reason :no-workspace}

          (not ext)
          {:ok false :reason :eca-extension-unavailable}

          :else
          (p/let [_ (.activate ext)
                  config-uri (vscode/Uri.joinPath root-uri ".eca" "config.json")
                  text (read-text!+ config-uri)
                  wrapper-path (cursor-config/wrapper-script-path
                                {:vscode/extension-context extension-context
                                 :cursor/script-relative-path script-relative-path})
                  port-file-path (some-> port-file-uri (unchecked-get "fsPath"))
                  desired (eca-config/desired-entry wrapper-path port-file-path host)
                  plan (eca-config/plan-config-text text server-name desired)]
            (if (= :no-op (:eca/action plan))
              {:ok true :action :no-op}
              (p/let [_ (write-text!+ config-uri (:eca/text plan))]
                {:ok true :action :write})))))
      (p/catch (fn [err]
                 {:ok false :error err}))))
