(ns vscode-mcp.wrapper-install
  "Install the stdio wrapper script into a stable consumer directory."
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [vscode-mcp.cursor-config :as cursor-config]))

(defn installed-path
  "Absolute path of the installed wrapper: install-dir + basename(script-relative-path)."
  [install-dir script-relative-path]
  (path/join install-dir (path/basename script-relative-path)))

(defn ensure-installed!
  "Ensure wrapper exists under `:lifecycle/wrapper-install-dir`.
   Source: extensionPath + `:cursor/script-relative-path`.
   goog.DEBUG → symlinkSync; else copyFileSync.
   Unlink dest first. mkdirSync recursive. Returns dest path. No fallback from symlink to copy."
  [{:vscode/keys [extension-context]
    :cursor/keys [script-relative-path]
    :lifecycle/keys [wrapper-install-dir]}]
  (let [source-path (cursor-config/wrapper-script-path
                     {:vscode/extension-context extension-context
                      :cursor/script-relative-path script-relative-path})
        dest-path (installed-path wrapper-install-dir script-relative-path)]
    (fs/mkdirSync wrapper-install-dir #js {:recursive true})
    (try (fs/unlinkSync dest-path) (catch :default _e))
    (if js/goog.DEBUG
      (fs/symlinkSync source-path dest-path)
      (fs/copyFileSync source-path dest-path))
    dest-path))
