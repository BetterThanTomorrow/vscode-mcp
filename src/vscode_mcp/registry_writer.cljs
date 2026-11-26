(ns vscode-mcp.registry-writer
  "The window's registry entry: written when the server starts, again shortly after its
   custom data changed, and on a heartbeat. What an entry holds is data, kept by server,
   and each write is synchronous. The timers are kept apart from the data. A timer that
   fires after the server stopped finds no entry, and writes nothing."
  (:require
   [promesa.core :as p]
   [vscode-mcp.mcp-media :as mcp-media]
   [vscode-mcp.registry :as registry]
   [vscode-mcp.registry-listing :as listing]))

(defonce ^{:private true
           :doc "By server: the entry as data, its custom data, the file it is written to, and its window's slug."}
  !entries
  (atom {}))

(defonce ^{:private true
           :doc "By server: the timer of the next write after a change, and the heartbeat's."}
  !timers
  (atom {}))

(defn- writer-key
  [config server-info]
  [(:server/name config)
   (:server/instance-slug server-info)])

(defn- find-key
  [server-name]
  (some (fn [k]
          (when (= server-name (first k))
            k))
        (keys @!entries)))

(defn- clear-timers!
  [key]
  (when-let [{:keys [debounce-timer heartbeat-timer]} (get @!timers key)]
    (when debounce-timer
      (js/clearTimeout debounce-timer))
    (when heartbeat-timer
      (js/clearInterval heartbeat-timer)))
  (swap! !timers dissoc key))

(defn clear-writers!
  "Test helper: drop all entries and their timers."
  []
  (doseq [key (keys @!timers)]
    (clear-timers! key))
  (reset! !entries {})
  (mcp-media/clear-media-state!))

(defn- custom-data
  "What the entry holds beside the library's own fields: the map in `:registry/custom-data`."
  [config]
  (let [data (:registry/custom-data config)]
    (if (map? data)
      data
      {})))

(defn- write-entry!
  "Writes the entry of `key` with the time of the write, when the server has an entry."
  [key]
  (when-let [{:registry/keys [envelope custom-data path]} (get @!entries key)]
    (registry/atomic-write! path (-> envelope
                                     (registry/merge-custom-data custom-data)
                                     (assoc :updatedAt (registry/now-iso))))))

(defn- start-heartbeat!
  [key config]
  (let [ms (:registry/heartbeat-ms config registry/default-heartbeat-ms)]
    (swap! !timers assoc-in [key :heartbeat-timer]
           (js/setInterval #(write-entry! key) ms))))

(defn- schedule-write!
  "Writes the entry of `key` after the debounce time. A write that still waits is
   dropped for this one."
  [key config]
  (let [ms (:registry/debounce-ms config registry/default-debounce-ms)]
    (when-let [timer (get-in @!timers [key :debounce-timer])]
      (js/clearTimeout timer))
    (swap! !timers assoc-in [key :debounce-timer]
           (js/setTimeout #(write-entry! key) ms))))

(defn on-started!+
  "Sweeps dead entries, starts the heartbeat, and writes the entry with the custom data
   in `config`."
  [config server-info]
  (if-not (and (:registry/enabled? config)
               (:server/name config)
               (:server/instance-slug server-info))
    (p/resolved nil)
    (let [key (writer-key config server-info)]
      (clear-timers! key)
      (registry/sweep-dead-pid-files! (registry/registry-dir config))
      (swap! !entries assoc key {:registry/envelope (registry/envelope-for config server-info nil)
                                 :registry/custom-data (custom-data config)
                                 :registry/path (registry/entry-path config
                                                                     (:server/name config)
                                                                     (:server/instance-slug server-info))
                                 :server/instance-slug (:server/instance-slug server-info)})
      (start-heartbeat! key config)
      (registry/set-window-id-env! (:vscode/extension-context config)
                                   (:server/instance-slug server-info))
      (listing/maybe-install!+ config)
      (mcp-media/on-started! {:config config :server-info server-info})
      (write-entry! key)
      (p/resolved nil))))

(defn on-stopping!
  "Forgets the server's entry and clears its timers, so nothing writes it again."
  [config]
  (when-let [key (find-key (:server/name config))]
    (mcp-media/on-stopping! {:config config
                             :server-info (select-keys (get @!entries key) [:server/instance-slug])})
    (clear-timers! key)
    (swap! !entries dissoc key)))

(defn on-stopped!
  "Unlinks the entry for `server-info`'s window."
  [config server-info]
  (when (and (:server/name config)
             (:server/instance-slug server-info))
    (registry/unlink-silent!
     (registry/entry-path config
                          (:server/name config)
                          (:server/instance-slug server-info)))))

(defn update-registry!+
  "Takes the custom data in `config` for the running server's entry, and writes the
   entry after the debounce time."
  [config]
  (when (:registry/enabled? config)
    (when-let [key (find-key (:server/name config))]
      (swap! !entries assoc-in [key :registry/custom-data] (custom-data config))
      (schedule-write! key config)))
  (p/resolved nil))
