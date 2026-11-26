(ns vscode-mcp.skills-install-test
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as string]
   [vscode-mcp.skills-install :as sut]))

(defn- temp-dir []
  (fs/mkdtempSync (path/join (os/tmpdir) "vscode-mcp-skills-")))

(defn- write-file! [p content]
  (fs/mkdirSync (path/dirname p) #js {:recursive true})
  (fs/writeFileSync p content))

(defn- skill-md [skill-name]
  (str "---\nname: " skill-name "\ndescription: The " skill-name " skill\n---\n\n# " skill-name "\n"))

(defn- fixture-manifest!
  "Writes an extension named `extension-name` under `root`, with a skill for each
   `[folder skill-name]`, and returns its manifest as data. The first skill has a
   reference file."
  [root extension-name skills]
  (let [extension-path (path/join root "extension")]
    (doseq [[folder skill-name] skills]
      (write-file! (path/join extension-path "skills" folder "SKILL.md") (skill-md skill-name)))
    (write-file! (path/join extension-path "skills" (ffirst skills) "references" "notes.md") "Notes")
    {:manifest/name extension-name
     :manifest/skills (mapv (fn [[folder]]
                              {"path" (str "skills/" folder "/SKILL.md")})
                            skills)
     :manifest/extension-path extension-path}))

(def ^:private two-skills [["alpha-folder" "alpha"] ["beta" "beta"]])

(defn- entries [dir]
  (set (fs/readdirSync dir)))

(defn- link? [p]
  (.isSymbolicLink (fs/lstatSync p)))

(defn- read-text [p]
  (fs/readFileSync p "utf8"))

(defn- with-temp-dir [f]
  (let [root (temp-dir)]
    (try
      (f root)
      (finally
        (fs/rmSync root #js {:recursive true :force true})))))

(deftest install-links-in-a-development-build
  (with-temp-dir
    (fn [root]
      (let [home (path/join root "skills")
            manifest (fixture-manifest! root "my-ext" two-skills)
            result (sut/install! (assoc manifest :skills/dir home :skills/link? true))
            alpha (path/join home "my-ext" "alpha")]
        (is (= [alpha (path/join home "my-ext" "beta")]
               (:skills/installed result))
            "Returns the paths it installed, named by the skills' frontmatter names")
        (is (= [] (:skills/failures result)))
        (is (= #{"alpha" "beta"} (entries (path/join home "my-ext")))
            "Leaves no temporary entries")
        (is (link? alpha))
        (is (= (fs/realpathSync (path/join (:manifest/extension-path manifest) "skills" "alpha-folder"))
               (fs/realpathSync alpha))
            "Links the skill's folder")
        (is (= (skill-md "alpha") (read-text (path/join alpha "SKILL.md"))))
        (is (= "Notes" (read-text (path/join alpha "references" "notes.md")))
            "Has the skill's sibling files")))))

(deftest install-copies-in-a-release
  (with-temp-dir
    (fn [root]
      (let [home (path/join root "skills")
            manifest (fixture-manifest! root "my-ext" two-skills)
            result (sut/install! (assoc manifest :skills/dir home :skills/link? false))
            alpha (path/join home "my-ext" "alpha")]
        (is (= 2 (count (:skills/installed result))))
        (is (= [] (:skills/failures result)))
        (is (not (link? alpha)))
        (is (.isDirectory (fs/lstatSync alpha)))
        (is (= (skill-md "alpha") (read-text (path/join alpha "SKILL.md"))))
        (is (= "Notes" (read-text (path/join alpha "references" "notes.md")))
            "Copies the skill's sibling files")))))

(deftest install-replaces-links-and-copies
  (with-temp-dir
    (fn [root]
      (let [home (path/join root "skills")
            manifest (assoc (fixture-manifest! root "my-ext" two-skills) :skills/dir home)
            alpha (path/join home "my-ext" "alpha")
            source-skill (path/join (:manifest/extension-path manifest) "skills" "alpha-folder" "SKILL.md")]
        (testing "A copy replaces a link"
          (sut/install! (assoc manifest :skills/link? true))
          (is (= [] (:skills/failures (sut/install! (assoc manifest :skills/link? false)))))
          (is (not (link? alpha)))
          (is (= (skill-md "alpha") (read-text source-skill))
              "Leaves what the link pointed at"))
        (testing "A link replaces a copy"
          (is (= [] (:skills/failures (sut/install! (assoc manifest :skills/link? true)))))
          (is (link? alpha))
          (is (= "Notes" (read-text (path/join alpha "references" "notes.md")))))
        (testing "A copy replaces a copy, with the skill as it is now"
          (sut/install! (assoc manifest :skills/link? false))
          (write-file! source-skill (str (skill-md "alpha") "Changed\n"))
          (is (= [] (:skills/failures (sut/install! (assoc manifest :skills/link? false)))))
          (is (= (str (skill-md "alpha") "Changed\n") (read-text (path/join alpha "SKILL.md")))))
        (is (= #{"alpha" "beta"} (entries (path/join home "my-ext"))))))))

(deftest install-removes-what-the-manifest-no-longer-declares
  (with-temp-dir
    (fn [root]
      (let [home (path/join root "skills")
            manifest (assoc (fixture-manifest! root "my-ext" two-skills) :skills/dir home)
            only-alpha (update manifest :manifest/skills (comp vec butlast))
            beta-source (path/join (:manifest/extension-path manifest) "skills" "beta" "SKILL.md")]
        (testing "A link is unlinked, and what it pointed at is left"
          (sut/install! (assoc manifest :skills/link? true))
          (let [result (sut/install! (assoc only-alpha :skills/link? true))]
            (is (= [(path/join home "my-ext" "beta")] (:skills/removed result)))
            (is (= #{"alpha"} (entries (path/join home "my-ext"))))
            (is (= (skill-md "beta") (read-text beta-source)))))
        (testing "A copy is removed"
          (sut/install! (assoc manifest :skills/link? false))
          (is (= [(path/join home "my-ext" "beta")]
                 (:skills/removed (sut/install! (assoc only-alpha :skills/link? false)))))
          (is (= #{"alpha"} (entries (path/join home "my-ext"))))
          (is (= (skill-md "beta") (read-text beta-source))))
        (testing "Another extension's skills are left"
          (sut/install! (assoc manifest :manifest/name "other-ext" :skills/link? true))
          (sut/install! (assoc only-alpha :skills/link? true))
          (is (= #{"alpha" "beta"} (entries (path/join home "other-ext")))))))))

(deftest install-does-nothing-for-a-blank-name
  (with-temp-dir
    (fn [root]
      (let [home (path/join root "skills")
            manifest (assoc (fixture-manifest! root "my-ext" two-skills) :skills/dir home :skills/link? true)]
        (doseq [blank [nil "" "  "]]
          (is (= {:skills/installed [] :skills/removed [] :skills/failures [] :skills/cleanup-failures []}
                 (sut/install! (assoc manifest :manifest/name blank)))
              (str "Installs nothing for " (pr-str blank))))
        (is (not (fs/existsSync home))
            "Doesn't make the skills home")))))

(deftest install-keeps-a-link-to-a-skill-working
  (with-temp-dir
    (fn [root]
      (let [home (path/join root "skills")
            manifest (assoc (fixture-manifest! root "my-ext" two-skills) :skills/dir home)
            agent-link (path/join root "agent-skills" "alpha")]
        (sut/install! (assoc manifest :skills/link? true))
        (fs/mkdirSync (path/dirname agent-link))
        (fs/symlinkSync (path/join home "my-ext" "alpha") agent-link "junction")
        (doseq [link-install? [true false false true]]
          (sut/install! (assoc manifest :skills/link? link-install?))
          (is (link? agent-link))
          (is (= (skill-md "alpha") (read-text (path/join agent-link "SKILL.md")))
              (str "A link to the skill reads it after an install that "
                   (if link-install? "links" "copies"))))))))

(deftest installs-interleaved-end-with-every-skill
  (doseq [link-install? [true false]]
    (with-temp-dir
      (fn [root]
        (let [home (path/join root "skills")
              manifest (assoc (fixture-manifest! root "my-ext" two-skills)
                              :skills/dir home
                              :skills/link? link-install?)
              _ (sut/install! manifest)
              _ (fs/mkdirSync (path/join home "my-ext" "gone"))
              plan-a (sut/install-plan (assoc manifest :skills/temporary-suffix "a"))
              plan-b (sut/install-plan (assoc manifest :skills/temporary-suffix "b"))
              result (sut/perform! (interleave plan-a plan-b))]
          (is (= [] (:skills/failures result)))
          (is (= #{"alpha" "beta"} (entries (path/join home "my-ext")))
              (str "Two installs that " (if link-install? "link" "copy")
                   " step by step end with every skill, and nothing else"))
          (is (= "Notes" (read-text (path/join home "my-ext" "alpha" "references" "notes.md")))))))))

(deftest a-failed-install-is-logged-and-returned
  (with-temp-dir
    (fn [root]
      (let [{:manifest/keys [extension-path skills]} (fixture-manifest! root "my-ext" two-skills)
            not-a-folder (path/join root "a-file")
            !logged (atom [])
            context #js {:extensionPath extension-path
                         :extension #js {:packageJSON #js {:name "my-ext"
                                                           :contributes #js {:chatSkills (clj->js skills)}}}}
            _ (fs/writeFileSync not-a-folder "")
            result (sut/install-from-config! {:vscode/extension-context context
                                              :skills/dir not-a-folder
                                              :mcp/on-log (fn [& args]
                                                            (swap! !logged conj args))}
                                             true)]
        (is (= [] (:skills/installed result)))
        (is (= [[:skills/step.ensure-dir (path/join not-a-folder "my-ext")]]
               (map :skills/step (:skills/failures result)))
            "Stops at the folder it can't make")
        (is (= :warn (ffirst @!logged))
            "Logs the failure")))))

;; A step performer for `perform!` that throws for the steps in `failing`, and records
;; the others in `!performed`.
(defn- performer [!performed failing]
  (fn [plan-step]
    (if (contains? failing plan-step)
      (throw (js/Error. "Failed"))
      (swap! !performed conj plan-step))
    nil))

(deftest a-failed-remove-leaves-the-later-removes-to-run
  (let [!performed (atom [])
        plan [[:skills/step.ensure-dir "/skills/my-ext"]
              [:skills/step.remove "/skills/my-ext/gone"]
              [:skills/step.remove "/skills/my-ext/also-gone"]
              [:skills/step.remove-leftover "/skills/my-ext/.alpha.installing-x"]
              [:skills/step.remove-leftover "/skills/my-ext/.beta.installing-y"]]
        result (sut/perform! (performer !performed #{(nth plan 1) (nth plan 3)}) plan)]
    (is (= [(nth plan 0) (nth plan 2) (nth plan 4)] @!performed)
        "A remove after one that failed runs")
    (is (= ["/skills/my-ext/also-gone" "/skills/my-ext/.beta.installing-y"] (:skills/removed result)))
    (is (= [(nth plan 1)] (map :skills/step (:skills/failures result))))
    (is (= [(nth plan 3)] (map :skills/step (:skills/cleanup-failures result)))
        "A leftover that can't be removed is a cleanup failure, apart from the install's")))

(deftest a-skill-whose-stage-failed-is-skipped
  (let [!performed (atom [])
        plan [[:skills/step.ensure-dir "/skills/my-ext"]
              [:skills/step.stage "/ext/alpha" "/skills/my-ext/.alpha.t" true]
              [:skills/step.put-in-place "/skills/my-ext/.alpha.t" "/skills/my-ext/alpha"]
              [:skills/step.stage "/ext/beta" "/skills/my-ext/.beta.t" true]
              [:skills/step.put-in-place "/skills/my-ext/.beta.t" "/skills/my-ext/beta"]
              [:skills/step.remove "/skills/my-ext/gone"]]
        result (sut/perform! (performer !performed #{(nth plan 1)}) plan)]
    (is (= [(nth plan 0) (nth plan 3) (nth plan 4) (nth plan 5)] @!performed)
        "The skill whose stage failed isn't put in place, and the other skill and the remove are done")
    (is (= ["/skills/my-ext/beta"] (:skills/installed result)))
    (is (= [(nth plan 1)] (map :skills/step (:skills/failures result))))))

(deftest install-removes-what-installs-left-behind
  (with-temp-dir
    (fn [root]
      (let [home (path/join root "skills")
            manifest (assoc (fixture-manifest! root "my-ext" two-skills) :skills/dir home :skills/link? false)
            extension-dir (path/join home "my-ext")
            leftovers [".alpha.installing-1234" ".alpha.installing-5678.old" ".gone.installing-9"]]
        (sut/install! manifest)
        (doseq [leftover leftovers]
          (fs/mkdirSync (path/join extension-dir leftover) #js {:recursive true}))
        (fs/writeFileSync (path/join extension-dir ".alpha.installing-1234" "SKILL.md") "Partial")
        (fs/symlinkSync (path/join root "extension" "skills" "beta") (path/join extension-dir ".beta.installing-42") "junction")
        (fs/writeFileSync (path/join extension-dir ".DS_Store") "")
        (testing "An install in progress, a minute or less ago, is left"
          (is (= [] (:skills/removed (sut/install! manifest))))
          (is (= 5 (count (filter #(string/starts-with? % ".") (entries extension-dir))))))
        (testing "Older ones are removed, a link by unlinking it"
          (let [result (sut/install! (assoc manifest :skills/now (+ (js/Date.now) 120000)))]
            (is (= #{"alpha" "beta" ".DS_Store"} (entries extension-dir))
                "Leaves the skills and what isn't an install's")
            (is (= 4 (count (:skills/removed result))))
            (is (= [] (:skills/cleanup-failures result)))
            (is (= (skill-md "beta") (read-text (path/join root "extension" "skills" "beta" "SKILL.md")))
                "Leaves what a left behind link pointed at")))))))

(deftest install-leaves-the-entries-of-a-linked-extension-folder
  (with-temp-dir
    (fn [root]
      (let [home (path/join root "skills")
            elsewhere (path/join root "elsewhere")
            manifest (assoc (fixture-manifest! root "my-ext" two-skills) :skills/dir home :skills/link? true)]
        (fs/mkdirSync (path/join elsewhere "my-own-skill") #js {:recursive true})
        (fs/mkdirSync home)
        (fs/symlinkSync elsewhere (path/join home "my-ext") "junction")
        (let [result (sut/install! manifest)]
          (is (= 2 (count (:skills/installed result))))
          (is (= [] (:skills/removed result)))
          (is (= #{"alpha" "beta" "my-own-skill"} (entries elsewhere))
              "Installs the skills, and leaves the folder's other entries"))))))

(deftest a-failed-removal-of-the-old-skill-is-a-cleanup-failure
  ;; A folder that can't be removed takes a read-only folder, which Windows doesn't have.
  (when-not (= "win32" js/process.platform)
    (with-temp-dir
      (fn [root]
        (let [home (path/join root "skills")
              manifest (assoc (fixture-manifest! root "my-ext" two-skills) :skills/dir home :skills/link? false)
              old-references (path/join home "my-ext" "alpha" "references")
              !logged (atom [])]
          (sut/install! manifest)
          (fs/chmodSync old-references 0555)
          (try
            (let [{:manifest/keys [extension-path skills]} manifest
                  context #js {:extensionPath extension-path
                               :extension #js {:packageJSON #js {:name "my-ext"
                                                                 :contributes #js {:chatSkills (clj->js skills)}}}}
                  result (sut/install-from-config! {:vscode/extension-context context
                                                    :skills/dir home
                                                    :mcp/on-log (fn [& args]
                                                                  (swap! !logged conj args))}
                                                   false)]
              (is (= 2 (count (:skills/installed result))))
              (is (= [] (:skills/failures result))
                  "The install succeeded")
              (is (= 1 (count (:skills/cleanup-failures result)))
                  "The old skill that couldn't be removed is a cleanup failure")
              (is (= ["[MCP] Skill install cleanup failed:"] (map second @!logged))
                  "and is logged as one")
              (is (= "Notes" (read-text (path/join home "my-ext" "alpha" "references" "notes.md")))))
            (finally
              (doseq [entry (fs/readdirSync (path/join home "my-ext"))]
                (let [references (path/join home "my-ext" entry "references")]
                  (when (fs/existsSync references)
                    (fs/chmodSync references 0755)))))))))))

(deftest install-from-config-installs-into-the-skills-dir
  (with-temp-dir
    (fn [root]
      (let [{:manifest/keys [extension-path skills]} (fixture-manifest! root "my-ext" two-skills)
            home (path/join root "skills")
            context #js {:extensionPath extension-path
                         :extension #js {:packageJSON #js {:name "my-ext"
                                                           :contributes #js {:chatSkills (clj->js skills)}}}}
            result (sut/install-from-config! {:vscode/extension-context context
                                              :skills/dir home}
                                             false)]
        (is (= [(path/join home "my-ext" "alpha") (path/join home "my-ext" "beta")]
               (:skills/installed result)))))))

(deftest skills-dir-defaults-to-the-skills-home
  (is (= (path/join (os/homedir) ".config" "vscode-mcp" "skills")
         (sut/skills-dir {})))
  (is (= "/some/dir" (sut/skills-dir {:skills/dir "/some/dir"}))))
