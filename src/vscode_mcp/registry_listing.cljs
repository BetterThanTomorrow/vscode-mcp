(ns vscode-mcp.registry-listing
  "Install registry-home support files (docs, bb list) on first MCP start."
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [promesa.core :as p]
   [vscode-mcp.registry :as registry]
   [vscode-mcp.registry-listing-stubs :as stubs]))

(def consumer-files
  ["README.md" "AGENTS.md" "bb.edn" "scripts/list_registry.clj"])

(def github-base
  "https://raw.githubusercontent.com/BetterThanTomorrow/vscode-mcp/master/assets/registry-content/")

(def fetch-timeout-ms
  15000)

(defonce !install-ok? (atom false))
(defonce !fetch-override!+ (atom nil))

(defn clear-install-state!
  "Test helper: drop the process-local successful-install flag and fetch override."
  []
  (reset! !install-ok? false)
  (reset! !fetch-override!+ nil))

(defn registry-home
  "Directory that holds README, AGENTS.md, bb.edn, scripts/, and windows/."
  [config]
  (path/dirname (registry/registry-dir config)))

(defn sibling-content-dir
  [config]
  (when-let [ext-path (some-> ^js (:vscode/extension-context config) .-extensionPath)]
    (path/join ext-path ".." "vscode-mcp" "assets" "registry-content")))

(defn sibling-usable?
  [dir]
  (boolean
   (and dir
        (every? #(fs/existsSync (path/join dir %)) consumer-files))))

(defn read-utf8
  [file-path]
  (when (fs/existsSync file-path)
    (fs/readFileSync file-path "utf8")))

(defn write-if-changed!
  "Writes `content` to `dest-path` when the file is missing or bytes differ."
  [dest-path content]
  (when (not= (read-utf8 dest-path) content)
    (fs/mkdirSync (path/dirname dest-path) #js {:recursive true})
    (fs/writeFileSync dest-path content)
    true))

(defn write-files!
  [home files]
  (doseq [[rel content] files]
    (write-if-changed! (path/join home rel) content)))

(defn listing-usable?
  "True when `home` already has bb.edn or a README that is not the first-fail stub."
  [home]
  (boolean
   (or (fs/existsSync (path/join home "bb.edn"))
       (let [readme (read-utf8 (path/join home "README.md"))]
         (and readme (not= readme stubs/readme))))))

(defn write-stubs!
  [home]
  (write-if-changed! (path/join home "README.md") stubs/readme)
  (write-if-changed! (path/join home "AGENTS.md") stubs/agents))

(defn- log-warn
  [config & args]
  (when-let [on-log (:mcp/on-log config)]
    (apply on-log :warn args)))

(defn fetch-text!+
  [url]
  (let [ctrl (js/AbortController.)
        timer (js/setTimeout #(.abort ctrl) fetch-timeout-ms)
        opts (js-obj "signal" (.-signal ctrl))]
    (-> (p/let [res (js/fetch url opts)]
          (if (.-ok res)
            (.text res)
            (throw (ex-info "registry listing fetch failed"
                            {:url url :status (.-status res)}))))
        (p/finally (fn [] (js/clearTimeout timer))))))

(defn fetch-github-files!+
  []
  (if-let [f @!fetch-override!+]
    (f)
    (p/all
     (mapv (fn [rel]
             (p/let [text (fetch-text!+ (str github-base rel))]
               [rel text]))
           consumer-files))))

(defn install-from-github!+
  [config home]
  (-> (p/let [files (fetch-github-files!+)]
        (write-files! home files)
        (reset! !install-ok? true)
        :github)
      (p/catch (fn [err]
                 (log-warn config "[MCP] registry listing GitHub fetch failed:" err)
                 (when-not (listing-usable? home)
                   (write-stubs! home))
                 :github-failed))))

(defn copy-sibling!
  [src-dir home]
  (write-files!
   home
   (map (fn [rel]
          [rel (fs/readFileSync (path/join src-dir rel) "utf8")])
        consumer-files))
  (reset! !install-ok? true)
  :sibling)

(defn install!+
  [config]
  (let [home (registry-home config)
        sibling (sibling-content-dir config)]
    (cond
      (not (:vscode/extension-context config))
      (p/resolved :skipped-no-context)

      (and js/goog.DEBUG (sibling-usable? sibling))
      (p/resolved (copy-sibling! sibling home))

      :else
      (install-from-github!+ config home))))

(defn maybe-install!+
  "Fire-and-forget from on-started. Skips after a successful install this session."
  [config]
  (cond
    (not (:registry/enabled? config))
    (p/resolved :skipped)

    @!install-ok?
    (p/resolved :skipped)

    :else
    (-> (install!+ config)
        (p/catch (fn [err]
                   (log-warn config "[MCP] registry listing install failed:" err)
                   :failed)))))
