(ns vscode-mcp.stdio.connect-policy
  (:require
   [vscode-mcp.stdio-config :as stdio-config]))

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

(defn connect-host
  "The host to dial for a server on `host`: loopback for an unspecified address, and the
   default host for a blank one."
  [host]
  (let [host (stdio-config/normalize-host host)]
    (get {"0.0.0.0" "127.0.0.1"
          "::" "::1"}
         host
         host)))
