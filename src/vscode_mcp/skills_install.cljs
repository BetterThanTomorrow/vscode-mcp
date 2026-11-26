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

(def ^:private temporary-marker
  "What the temporary names that an install puts skills in place under start with,
   after the skill's name."
  "installing-")

(def ^:private leftover-age-ms
  "How old a temporary entry is when it counts as left behind."
  60000)

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
  "The names of what is in `dir`, none when it isn't a folder."
  [dir]
  (try
    (vec (fs/readdirSync dir))
    (catch :default e
      (if (contains? #{"ENOENT" "ENOTDIR"} (.-code e))
        []
        (throw e)))))

(defn- lstat
  [p]
  (try
    (fs/lstatSync p)
    (catch :default _
      nil)))

(defn- error-message [e]
  (or (ex-message e) (str e)))

(defn- leftover?
  "Whether `entry`, with the stat `stat`, is a skill that an install put in place
   under a temporary name, or moved aside, and left behind: older than
   `leftover-age-ms` at `now`. An install in progress in another window has its
   entries there for milliseconds."
  [entry ^js stat now]
  (and stat
       (temporary-name? entry)
       (string/includes? entry (str "." temporary-marker))
       (< (+ (max (.-mtimeMs stat) (.-ctimeMs stat)) leftover-age-ms) now)))

(defn- removal-steps
  "The steps that remove what `extension-dir` holds beside the skills named in
   `declared`: what earlier installs left behind, and the entries the manifest no longer
   declares. When `extension-dir` is itself a link, to a folder that may hold more, it
   leaves those entries."
  [extension-dir declared now]
  (let [linked? (some-> (lstat extension-dir) (.isSymbolicLink))]
    (into []
          (keep (fn [entry]
                  (let [p (path/join extension-dir entry)]
                    (cond
                      (temporary-name? entry) (when (leftover? entry (lstat p) now)
                                                [:skills/step.remove-leftover p])
                      (or linked? (contains? declared entry)) nil
                      :else [:skills/step.remove p]))))
          (entries extension-dir))))

(defn install-plan
  "Returns the steps that make `<dir>/<manifest name>/` hold every skill `manifest`
   declares, and nothing else, from what is there now. `temporary-suffix` makes the
   names that skills are put in place under this install's own. `now`, by default the
   current time, tells what earlier installs left behind from what one in progress has
   there."
  [{:manifest/keys [name] :skills/keys [dir link? temporary-suffix now] :as manifest}]
  (if-not (folder-name? name)
    []
    (let [extension-dir (path/join dir name)
          skills (declared-skills manifest)]
      (-> [[:skills/step.ensure-dir extension-dir]]
          (into (mapcat (fn [{skill-name :skill/name :skill/keys [source-dir]}]
                          (let [temporary (path/join extension-dir (str "." skill-name "." temporary-suffix))]
                            [[:skills/step.stage source-dir temporary link?]
                             [:skills/step.put-in-place temporary (path/join extension-dir skill-name)]])))
                skills)
          (into (removal-steps extension-dir
                               (set (map :skill/name skills))
                               (or now (js/Date.now))))))))

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
   still fails and nothing else has taken its place. Returns the path of what it moved
   aside, for the caller to remove once the skill is in place, or nil."
  [temporary dest]
  (try
    (fs/renameSync temporary dest)
    nil
    (catch :default _
      (let [aside (str temporary ".old")]
        (fs/renameSync dest aside)
        (try
          (fs/renameSync temporary dest)
          aside
          (catch :default e
            (when-not (lstat dest)
              (fs/renameSync aside dest))
            (throw e)))))))

(defn- cleanup-failure
  "Removes `p`, which is left over. Returns the failure, as data, when it couldn't."
  [p]
  (try
    (remove-entry! p)
    nil
    (catch :default e
      {:skills/path p
       :error/message (error-message e)})))

(defn- put-in-place!
  "Renames `temporary` over `dest`, and then removes what is left: the temporary entry
   when the rename failed, and the old skill when it was moved aside. Returns the
   removals that failed, as data. A removal that fails leaves an entry that a later
   install removes."
  [temporary dest]
  (let [aside (try
                (rename-over! temporary dest)
                (catch :default e
                  (cleanup-failure temporary)
                  (throw e)))]
    (vec (keep cleanup-failure (cond-> [temporary]
                                 aside (conj aside))))))

(defn- perform-step!
  "Does what `plan-step` says. For a skill put in place, returns the cleanup that
   failed, as data."
  [[step & args]]
  (case step
    :skills/step.ensure-dir (fs/mkdirSync (first args) #js {:recursive true})
    :skills/step.stage (apply stage! args)
    :skills/step.put-in-place (apply put-in-place! args)
    (:skills/step.remove :skills/step.remove-leftover) (remove-entry! (first args))))

(defn- skill-key
  "The temporary name a skill step puts its skill in place under, which the steps for
   one skill share."
  [[step & args]]
  (case step
    :skills/step.stage (second args)
    :skills/step.put-in-place (first args)
    nil))

(defn- performed
  "Adds what `plan-step` did to `result`, from what performing it returned: the
   cleanup that failed, for a skill put in place."
  [result [step & args] step-result]
  (case step
    :skills/step.put-in-place (-> result
                                  (update :skills/installed conj (second args))
                                  (update :skills/cleanup-failures into step-result))
    (:skills/step.remove :skills/step.remove-leftover) (update result :skills/removed conj (first args))
    result))

(defn- failed
  "Adds the failure of `plan-step` to `result`: a leftover's removal as cleanup, and
   the others as the install's. A skill whose step failed is skipped after that, and a
   folder that can't be made ends the install."
  [result plan-step e]
  (let [failure {:skills/step plan-step
                 :error/message (error-message e)}
        skill (skill-key plan-step)]
    (case (first plan-step)
      :skills/step.ensure-dir (reduced (update result :skills/failures conj failure))
      :skills/step.remove-leftover (update result :skills/cleanup-failures conj failure)
      (cond-> (update result :skills/failures conj failure)
        skill (update :skills/skipped conj skill)))))

(defn perform!
  "Performs the steps of an install plan, each with `perform-step`, by default the one
   that does what the step says. Returns the paths it put skills in and the ones it
   removed, the steps that failed, and the cleanup that failed, each with its error's
   message. A skill whose step failed is skipped after that, and nothing is done after
   a folder that can't be made."
  ([steps]
   (perform! perform-step! steps))
  ([perform-step steps]
   (-> (reduce (fn [result plan-step]
                 (if (contains? (:skills/skipped result) (skill-key plan-step))
                   result
                   (try
                     (performed result plan-step (perform-step plan-step))
                     (catch :default e
                       (failed result plan-step e)))))
               {:skills/installed []
                :skills/removed []
                :skills/failures []
                :skills/cleanup-failures []
                :skills/skipped #{}}
               steps)
       (dissoc :skills/skipped))))

(defn install!
  "Makes `<dir>/<manifest name>/` hold every skill `manifest` declares, linked when
   `link?` and copied otherwise, and nothing else. Does nothing for a blank name.
   Returns what `perform!` returns. Doesn't throw: what failed is in `:skills/failures`."
  [manifest]
  (try
    (perform! (install-plan (merge {:skills/temporary-suffix (str temporary-marker (random-uuid))}
                                   manifest)))
    (catch :default e
      {:skills/installed []
       :skills/removed []
       :skills/failures [{:error/message (error-message e)}]
       :skills/cleanup-failures []})))

(defn install-from-config!
  "Installs the skills of the extension that `config`'s `:vscode/extension-context` is
   the context of, into `:skills/dir`, by default the skills home. Logs what failed
   through `:mcp/on-log`, the install and the cleanup apart, and returns what `install!`
   returns."
  [{:vscode/keys [extension-context] :mcp/keys [on-log] :as config} link?]
  (let [result (install! (merge (manifest/manifest-data extension-context)
                                {:skills/dir (skills-dir config)
                                 :skills/link? link?}))]
    (when on-log
      (doseq [failure (:skills/failures result)]
        (on-log :warn "[MCP] Skill install failed:" (pr-str failure)))
      (doseq [failure (:skills/cleanup-failures result)]
        (on-log :warn "[MCP] Skill install cleanup failed:" (pr-str failure))))
    result))
