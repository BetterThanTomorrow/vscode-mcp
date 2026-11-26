(ns vscode-mcp.lifecycle-turns
  "The lifecycle calls for one server run one after the other. A start, a stop or a
   registration waits until the call before it for the same server has settled, so each
   resolves to the outcome of its own operation, and not to the state of one that is
   still in progress.

   The call in progress is kept by server name, as a promise: a live object beside the
   state.")

(defonce ^:private !calls (atom {}))

(defn- forget-call!
  [!in-progress server-name call]
  (swap! !in-progress (fn [calls]
                        (if (identical? call (get calls server-name))
                          (dissoc calls server-name)
                          calls))))

(defn in-turn+
  "Calls `f`, which returns a promise or a value, when the call before it for
   `server-name` has resolved or rejected. Returns a promise of what `f` resolves to.
   A call that rejected or threw doesn't keep the next one from running."
  ([server-name f]
   (in-turn+ !calls server-name f))
  ([!in-progress server-name f]
   (let [previous (get @!in-progress server-name (js/Promise.resolve nil))
         call (-> previous
                  (.then (constantly nil) (constantly nil))
                  (.then (fn [_] (f))))]
     (swap! !in-progress assoc server-name call)
     (.then call
            (fn [_] (forget-call! !in-progress server-name call))
            (fn [_] (forget-call! !in-progress server-name call)))
     call)))
