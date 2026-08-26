(ns vscode-mcp.registry-writer
  "Process-local registry writer: debounce, heartbeat, generation fencing."
  (:require
   [promesa.core :as p]
   [vscode-mcp.registry :as registry]))

(defonce !writers (atom {}))
(defonce !generations (atom {}))

(defn- writer-key
  [config server-info]
  [(:cursor/server-name config)
   (:server/instance-slug server-info)])

(defn- find-key
  [server-name]
  (some (fn [k]
          (when (= server-name (first k))
            k))
        (keys @!writers)))

(defn- bump-generation!
  [key]
  (get (swap! !generations update key (fnil inc 0)) key))

(defn- current-generation
  [key]
  (get @!generations key))

(defn- current-entry
  [key generation]
  (when-let [entry (get @!writers key)]
    (when (= generation (:generation entry))
      entry)))

(defn- clear-timers!
  [entry]
  (when-let [t (:debounce-timer entry)]
    (js/clearTimeout t))
  (when-let [t (:heartbeat-timer entry)]
    (js/clearInterval t)))

(defn clear-writers!
  "Test helper: drop all writers and their timers."
  []
  (doseq [[_ entry] @!writers]
    (clear-timers! entry))
  (reset! !writers {})
  (reset! !generations {}))

(defn- log-warn
  [config & args]
  (when-let [on-log (:mcp/on-log config)]
    (apply on-log :warn args)))

(defn- write-if-current!
  [key generation payload]
  (when-let [entry (current-entry key generation)]
    (let [config (:config entry)
          info (:server-info entry)
          dest (registry/shard-path config
                                    (:cursor/server-name config)
                                    (:server/instance-slug info))]
      (registry/atomic-write! dest payload)
      (when (current-entry key generation)
        (swap! !writers assoc-in [key :last-payload] payload)))))

(defn- custom-data+
  [config]
  (if-let [f (:registry/custom-data+ config)]
    (-> (p/let [data (f {})]
          (if (map? data) data {}))
        (p/catch (fn [err]
                   (log-warn config "[MCP] registry custom-data failed:" err)
                   {})))
    (p/resolved {})))

(defn- refresh-and-write!+
  [key]
  (let [{:keys [config server-info generation]} (get @!writers key)]
    (if-not config
      (p/resolved nil)
      (p/let [custom (custom-data+ config)]
        (when (= generation (current-generation key))
          (write-if-current! key generation
                             (registry/envelope-for config server-info custom)))))))

(defn- heartbeat-tick!
  [key]
  (let [{:keys [last-payload generation]} (get @!writers key)]
    (when last-payload
      (write-if-current! key generation
                         (assoc last-payload :updatedAt (registry/now-iso))))))

(defn- start-heartbeat!
  [key]
  (let [config (:config (get @!writers key))
        ms (:registry/heartbeat-ms config registry/default-heartbeat-ms)
        timer (js/setInterval #(heartbeat-tick! key) ms)]
    (swap! !writers assoc-in [key :heartbeat-timer] timer)))

(defn- schedule-refresh!
  [key]
  (let [entry (get @!writers key)
        config (:config entry)
        ms (:registry/debounce-ms config registry/default-debounce-ms)]
    (when-let [t (:debounce-timer entry)]
      (js/clearTimeout t))
    (let [timer (js/setTimeout #(refresh-and-write!+ key) ms)]
      (swap! !writers assoc-in [key :debounce-timer] timer))))

(defn on-started!+
  "Sweeps dead shards, starts the heartbeat, and writes the initial shard."
  [config server-info]
  (if-not (and (:registry/enabled? config)
               (:cursor/server-name config)
               (:server/instance-slug server-info))
    (p/resolved nil)
    (let [key (writer-key config server-info)
          existing (get @!writers key)
          generation (bump-generation! key)]
      (when existing
        (clear-timers! existing))
      (registry/sweep-dead-pid-files! (registry/registry-dir config))
      (swap! !writers assoc key {:generation generation
                                 :config config
                                 :server-info server-info
                                 :last-payload nil
                                 :debounce-timer nil
                                 :heartbeat-timer nil})
      (start-heartbeat! key)
      (refresh-and-write!+ key))))

(defn on-stopping!
  "Synchronously fences in-flight writes and clears timers."
  [config]
  (when-let [key (find-key (:cursor/server-name config))]
    (when-let [entry (get @!writers key)]
      (clear-timers! entry)
      (bump-generation! key)
      (swap! !writers dissoc key))))

(defn on-stopped!
  "Unlinks the shard for `server-info`'s window."
  [config server-info]
  (when (and (:cursor/server-name config)
             (:server/instance-slug server-info))
    (registry/unlink-silent!
     (registry/shard-path config
                          (:cursor/server-name config)
                          (:server/instance-slug server-info)))))

(defn update-registry!+
  "Debounced refresh of `:registry/custom-data+` using live server-info."
  [config]
  (if-not (:registry/enabled? config)
    (p/resolved nil)
    (if-let [key (find-key (:cursor/server-name config))]
      (do
        (swap! !writers assoc-in [key :config] config)
        (schedule-refresh! key)
        (p/resolved nil))
      (p/resolved nil))))
