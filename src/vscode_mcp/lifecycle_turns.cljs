(ns vscode-mcp.lifecycle-turns
  "The lifecycle calls for one server run one after the other. A start, a stop, a
   registration or a registry update waits until the call before it for the same server
   has settled, so each resolves to the outcome of its own operation, and not to the
   state of one that is still in progress. A call that doesn't settle within a limit is
   waited for no longer: the next one runs, and the actions' guards protect the state.

   The call in progress is kept by server name, as a pair of promises: when it started
   and when it settled, a live object beside the state.")

(defonce ^:private !calls (atom {}))

(def default-wait-ms
  "How long a call waits for the one before it, when that one doesn't settle."
  30000)

(defn- forget-call!
  [!in-progress server-name call]
  (swap! !in-progress (fn [calls]
                        (if (identical? call (get calls server-name))
                          (dissoc calls server-name)
                          calls))))

(defn- waited+
  "Returns a promise that resolves when the call `turn` has settled, to false, or when
   it has run for `wait-ms` without settling, to true. With no call, it resolves at once."
  [{:turn/keys [started settled]} wait-ms]
  (if-not settled
    (js/Promise.resolve false)
    (js/Promise.
     (fn [resolve]
       (let [!timer (atom nil)
             settle (fn [waited-out?]
                      (some-> @!timer js/clearTimeout)
                      (reset! !timer :settled)
                      (resolve waited-out?))]
         (.then settled (fn [_] (settle false)) (fn [_] (settle false)))
         (.then started (fn [_]
                          (when-not (= :settled @!timer)
                            (reset! !timer (js/setTimeout #(settle true) wait-ms))))))))))

(defn in-turn+
  "Calls `f`, which returns a promise or a value, when the call before it for
   `server-name` has resolved or rejected, or has run for `:turns/wait-ms` without
   settling, by default `default-wait-ms`. Then it calls `:turns/on-waited-out`, when
   given, before `f`. Returns a promise of what `f` resolves to. A call that rejected,
   threw or never settles doesn't keep the next one from running."
  ([server-name f]
   (in-turn+ server-name nil f))
  ([server-name turn-options f]
   (in-turn+ !calls server-name turn-options f))
  ([!in-progress server-name {:turns/keys [wait-ms on-waited-out]} f]
   (let [started (.then (waited+ (get @!in-progress server-name) (or wait-ms default-wait-ms))
                        (fn [waited-out?]
                          (when (and waited-out? on-waited-out)
                            (on-waited-out))))
         settled (.then started (fn [_] (f)))
         turn {:turn/started started
               :turn/settled settled}]
     (swap! !in-progress assoc server-name turn)
     (.then settled
            (fn [_] (forget-call! !in-progress server-name turn))
            (fn [_] (forget-call! !in-progress server-name turn)))
     settled)))
