(ns vendor-jsonc-parser
  "Bundle microsoft/jsonc-parser into a single classpath-ready ESM file."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

(def default-version
  "3.3.1")

(def package-name
  "jsonc-parser")

(def esbuild-version
  "0.25.5")

(def vendor-dir
  "src/vscode_mcp/vendor")

(def outfile
  (str vendor-dir "/jsonc_parser.js"))

(def version-file
  (str vendor-dir "/jsonc_parser.VERSION"))

(def cli-spec
  {:coerce {:version :string}
   :alias {:v :version}})

(defn- banner
  [version]
  (str "/*\n"
       " * Vendored " package-name "@" version "\n"
       " * Single-file ESM bundle for vscode-mcp classpath consumption.\n"
       " * Upstream: https://github.com/microsoft/node-jsonc-parser\n"
       " * License: MIT (Microsoft)\n"
       " * Regenerate: bb vendor-jsonc-parser [--version <ver>]\n"
       " */\n"))

(defn- resolve-version
  [opts]
  (or (some-> (:version opts) str/trim not-empty)
      default-version))

(defn- validate-opts
  [opts]
  (let [version (resolve-version opts)
        errors (cond-> []
                 (not (re-matches #"\d+\.\d+\.\d+.*" version))
                 (conj (str "Invalid version: " version)))]
    {:valid? (empty? errors)
     :errors errors
     :version version}))

(defn- shell!
  [opts & args]
  (let [result (apply p/shell (merge {:out :string :err :string :continue true} opts)
                      args)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Command failed: " (str/join " " args) "\n"
                           (:err result) (:out result))
                      {:args args
                       :exit (:exit result)
                       :out (:out result)
                       :err (:err result)})))
    result))

(defn- npm-pack!
  "Download package tarball into `dir`. Returns tarball path."
  [dir version]
  (let [spec (str package-name "@" version)
        result (shell! {:dir (str dir)} "npm" "pack" spec "--silent")
        tarball (-> (:out result) str/trim)]
    (when (str/blank? tarball)
      (throw (ex-info "npm pack produced no tarball name" {:version version})))
    (fs/path dir tarball)))

(defn- extract-package!
  [dir tarball]
  (shell! {:dir (str dir)} "tar" "-xzf" (str tarball))
  (fs/path dir "package"))

(defn- bundle-entry!
  "Bundle from package root so esbuild path comments stay relative/stable."
  [package-root out-js]
  (shell! {:dir (str package-root)}
          "npx" "--yes" (str "esbuild@" esbuild-version)
          "lib/esm/main.js"
          "--bundle"
          "--format=esm"
          "--platform=neutral"
          "--legal-comments=none"
          (str "--outfile=" (str (fs/absolutize out-js)))))

(defn- write-vendored!
  [version bundled-js]
  (fs/create-dirs vendor-dir)
  (spit outfile (str (banner version) bundled-js))
  (spit version-file (str version "\n")))

(defn- smoke-check!
  "Sanity-check the bundle exposes parse/modify/applyEdits."
  []
  (let [abs (str (fs/absolutize outfile))
        js (str "import { parse, modify, applyEdits } from 'file://" abs "';\n"
                "const text = '{ /* c */ \"a\": 1 }';\n"
                "const obj = parse(text);\n"
                "if (obj.a !== 1) throw new Error('parse failed');\n"
                "const edited = applyEdits(text, modify(text, ['a'], 2, {}));\n"
                "if (parse(edited).a !== 2) throw new Error('modify failed');\n"
                "console.log('ok');\n")
        result (shell! {} "node" "--input-type=module" "-e" js)]
    (when-not (str/includes? (:out result) "ok")
      (throw (ex-info "Smoke check did not report ok" result)))))

(defn vendor!
  "Bundle jsonc-parser into `src/vscode_mcp/vendor/jsonc_parser.js`.

  Options:
  - `:version` — npm version to vendor (default `3.3.1`)"
  [opts]
  (let [{:keys [valid? errors version]} (validate-opts opts)]
    (if-not valid?
      (do
        (doseq [e errors] (println (str "Error: " e)))
        (System/exit 1))
      (let [work (fs/create-temp-dir {:prefix "vscode-mcp-jsonc-"})
            tmp-bundle (fs/path work "jsonc_parser.bundled.js")]
        (try
          (println (str "Packing " package-name "@" version " …"))
          (let [tarball (npm-pack! work version)
                package-root (extract-package! work tarball)
                entry (fs/path package-root "lib" "esm" "main.js")]
            (when-not (fs/exists? entry)
              (throw (ex-info "Missing ESM entrypoint in package"
                              {:entry (str entry)})))
            (println (str "Bundling " entry " → " outfile " …"))
            (bundle-entry! package-root tmp-bundle)
            (write-vendored! version (slurp (str tmp-bundle)))
            (println "Smoke-checking bundle …")
            (smoke-check!)
            (println (str "Wrote " outfile " (" package-name "@" version ")"))
            (println (str "Wrote " version-file)))
          (finally
            (fs/delete-tree work)))))))
