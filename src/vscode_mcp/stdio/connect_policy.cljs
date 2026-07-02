(ns vscode-mcp.stdio.connect-policy)

(def default-options
  {:connect/timeout-ms 60000
   :connect/interval-ms 500})

(defn attempt-decision
  "Returns {:connect/action :retry :connect/delay-ms … :connect/elapsed-ms …}
   or {:connect/action :give-up :connect/elapsed-ms …}.
   Retries while elapsed < timeout-ms; gives up at or after timeout-ms."
  [{:connect/keys [start-ms now-ms]} options]
  (let [opts (merge default-options options)
        elapsed (- now-ms start-ms)
        timeout-ms (:connect/timeout-ms opts)]
    (if (< elapsed timeout-ms)
      {:connect/action :retry
       :connect/delay-ms (:connect/interval-ms opts)
       :connect/elapsed-ms elapsed}
      {:connect/action :give-up
       :connect/elapsed-ms elapsed})))
