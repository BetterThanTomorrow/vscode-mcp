(ns vscode-mcp.server-readiness
  "Socket accept readiness — waits until the MCP TCP listener accepts connections
   before Cursor registration spawns the stdio wrapper."
  (:require
   ["net" :as net]
   [promesa.core :as p]))

(def default-options
  {:readiness/timeout-ms 5000
   :readiness/interval-ms 100})

(defn attempt-decision
  "Pure retry vs give-up decision for socket readiness polling."
  [{:readiness/keys [start-ms now-ms]} opts]
  (let [{:readiness/keys [timeout-ms interval-ms]
         :or {timeout-ms 5000 interval-ms 100}} (merge default-options opts)
        elapsed (- now-ms start-ms)]
    (if (>= elapsed timeout-ms)
      {:readiness/action :give-up :readiness/elapsed-ms elapsed}
      {:readiness/action :retry
       :readiness/delay-ms interval-ms
       :readiness/elapsed-ms elapsed})))

(defn- probe-connect!+ [host port]
  (p/create
   (fn [resolve _reject]
     (let [^js socket (.connect net #js {:port port :host host})]
       (.once socket "connect"
              (fn []
                (.destroy socket)
                (resolve true)))
       (.once socket "error"
              (fn [_err]
                (when socket (.destroy socket))
                (resolve false)))))))

(defn wait-for-socket-accepting!+
  "Returns a promise resolving true when a TCP connect to host:port succeeds,
   false when the readiness budget is exhausted."
  [host port opts]
  (let [start-ms (js/Date.now)]
    (letfn [(step+ []
              (p/let [ready? (probe-connect!+ host port)]
                (cond
                  ready? true

                  (= :give-up
                     (:readiness/action
                      (attempt-decision {:readiness/start-ms start-ms
                                         :readiness/now-ms (js/Date.now)}
                                        opts)))
                  false

                  :else
                  (let [decision (attempt-decision {:readiness/start-ms start-ms
                                                      :readiness/now-ms (js/Date.now)}
                                                     opts)]
                    (p/then (p/delay (:readiness/delay-ms decision))
                            (fn [_] (step+)))))))]
      (step+))))
