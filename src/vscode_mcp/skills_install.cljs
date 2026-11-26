(ns vscode-mcp.skills-install
  "Installs the skills an extension's manifest declares into the skills home,
   `~/.config/vscode-mcp/skills`, one folder per skill under the extension's name:
   `<skills-home>/<extension-name>/<skill-name>/`, a link to the skill's folder in a
   development build and a copy in a release.

   The install is a plan, a vector of steps as data, and the steps performed one by one.
   Each skill is put in place under a temporary name next to the old one, and renamed
   over it. An entry the manifest no longer declares is removed: a link by unlinking it,
   so that what it points at is left alone, also when it is a junction on Windows."
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [clojure.string :as string]
   [vscode-mcp.manifest :as manifest]))

(defn default-dir
  []
  (path/join (os/homedir) ".config" "vscode-mcp" "skills"))

(defn skills-dir
  [config]
  (or (:skills/dir config) (default-dir)))

(defn- folder-name?
  "Whether `s` names a folder directly under another one."
  [s]
  (and (string? s)
       (not (string/blank? s))
       (not (contains? #{"." ".."} s))
       (= s (path/basename s))
       (not (string/includes? s "\\"))))

(defn- temporary-name?
  "Whether `entry` is a skill folder being put in place, by this install or another one."
  [entry]
  (string/starts-with? entry "."))

(defn- declared-skills
  "The skills `manifest` declares, each with its name, as its URI has it, and its folder."
  [manifest]
  (->> (manifest/get-resources manifest)
       (keep (fn [{skill-name :name :keys [skill-path]}]
               (when (folder-name? skill-name)
                 {:skill/name skill-name
                  :skill/source-dir (path/dirname skill-path)})))
       (vec)))

(defn- entries
  [dir]
  (try
    (vec (fs/readdirSync dir))
    (catch :default e
      (if (= "ENOENT" (.-code e))
        []
        (throw e)))))

(defn install-plan
  "Returns the steps that make `<dir>/<manifest name>/` hold every skill `manifest`
   declares, and nothing else, from what is there now. `temporary-suffix` makes the
   names that skills are put in place under this install's own."
  [{:manifest/keys [name] :skills/keys [dir link? temporary-suffix] :as manifest}]
  (if-not (folder-name? name)
    []
    (let [extension-dir (path/join dir name)
          skills (declared-skills manifest)
          declared (set (map :skill/name skills))]
      (-> [[:skills/step.ensure-dir extension-dir]]
          (into (mapcat (fn [{skill-name :skill/name :skill/keys [source-dir]}]
                          (let [temporary (path/join extension-dir (str "." skill-name "." temporary-suffix))]
                            [[:skills/step.stage source-dir temporary link?]
                             [:skills/step.put-in-place temporary (path/join extension-dir skill-name)]])))
                skills)
          (into (comp (remove declared)
                      (remove temporary-name?)
                      (map (fn [entry]
                             [:skills/step.remove (path/join extension-dir entry)])))
                (entries extension-dir))))))

(defn- lstat
  [p]
  (try
    (fs/lstatSync p)
    (catch :default _
      nil)))

(defn- remove-entry!
  "Removes `p`: a link by unlinking it, never by a recursive remove, which on Windows
   can follow a junction into what it points at."
  [p]
  (when-let [stat (lstat p)]
    (if (and (.isDirectory stat)
             (not (.isSymbolicLink stat)))
      (fs/rmSync p #js {:recursive true :force true})
      (fs/unlinkSync p))))

(defn- stage!
  [source-dir temporary link?]
  (remove-entry! temporary)
  (if link?
    (fs/symlinkSync source-dir temporary "junction")
    (fs/cpSync source-dir temporary #js {:recursive true})))

(defn- rename-over!
  "Renames `temporary` to `dest`. Where the rename can't replace what is at `dest`, a
   folder, or anything on Windows, it moves that aside first, and back when the rename
   still fails."
  [temporary dest]
  (try
    (fs/renameSync temporary dest)
    (catch :default _
      (let [aside (str temporary ".old")]
        (fs/renameSync dest aside)
        (try
          (fs/renameSync temporary dest)
          (catch :default e
            (when-not (lstat dest)
              (fs/renameSync aside dest))
            (throw e))
          (finally
            (remove-entry! aside)))))))

(defn- put-in-place!
  [temporary dest]
  (try
    (rename-over! temporary dest)
    (finally
      (remove-entry! temporary))))

(defn- perform-step!
  [[step & args]]
  (case step
    :skills/step.ensure-dir (fs/mkdirSync (first args) #js {:recursive true})
    :skills/step.stage (apply stage! args)
    :skills/step.put-in-place (apply put-in-place! args)
    :skills/step.remove (remove-entry! (first args))))

(defn- error-message [e]
  (or (ex-message e) (str e)))

(defn- skill-key
  "The temporary name a skill step puts its skill in place under, which the steps for
   one skill share."
  [[step & args]]
  (case step
    :skills/step.stage (second args)
    :skills/step.put-in-place (first args)
    nil))

(defn- performed
  [result [step & args]]
  (case step
    :skills/step.put-in-place (update result :skills/installed conj (second args))
    :skills/step.remove (update result :skills/removed conj (first args))
    result))

(defn- failed
  [result plan-step e]
  (let [failure (-> result
                    (update :skills/failures conj {:skills/step plan-step
                                                   :error/message (error-message e)})
                    (update :skills/skipped conj (skill-key plan-step)))]
    (if (= :skills/step.ensure-dir (first plan-step))
      (reduced failure)
      failure)))

(defn perform!
  "Performs the steps of an install plan. Returns the paths it put skills in and the
   ones it removed, and the steps that failed, each with its error's message. A skill
   whose step failed is skipped after that, and nothing is done after a folder that
   can't be made."
  [steps]
  (-> (reduce (fn [result plan-step]
                (if (contains? (:skills/skipped result) (skill-key plan-step))
                  result
                  (try
                    (perform-step! plan-step)
                    (performed result plan-step)
                    (catch :default e
                      (failed result plan-step e)))))
              {:skills/installed []
               :skills/removed []
               :skills/failures []
               :skills/skipped #{}}
              steps)
      (dissoc :skills/skipped)))

(defn install!
  "Makes `<dir>/<manifest name>/` hold every skill `manifest` declares, linked when
   `link?` and copied otherwise, and nothing else. Does nothing for a blank name.
   Returns what `perform!` returns. Doesn't throw: what failed is in `:skills/failures`."
  [manifest]
  (try
    (perform! (install-plan (merge {:skills/temporary-suffix (str "installing-" (random-uuid))}
                                   manifest)))
    (catch :default e
      {:skills/installed []
       :skills/removed []
       :skills/failures [{:error/message (error-message e)}]})))

(defn install-from-config!
  "Installs the skills of the extension that `config`'s `:vscode/extension-context` is
   the context of, into `:skills/dir`, by default the skills home. Logs what failed
   through `:mcp/on-log`, and returns what `install!` returns."
  [{:vscode/keys [extension-context] :mcp/keys [on-log] :as config} link?]
  (let [result (install! (merge (manifest/manifest-data extension-context)
                                {:skills/dir (skills-dir config)
                                 :skills/link? link?}))]
    (when on-log
      (doseq [failure (:skills/failures result)]
        (on-log :warn "[MCP] Skill install failed:" (pr-str failure))))
    result))
