(ns vscode-mcp.skills-install-test
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [cljs.test :refer [deftest is testing]]
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
          (is (= {:skills/installed [] :skills/removed [] :skills/failures []}
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
        (is (= 1 (count (:skills/failures result)))
            "Stops at the folder it can't make")
        (is (= :warn (ffirst @!logged))
            "Logs the failure")))))

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
