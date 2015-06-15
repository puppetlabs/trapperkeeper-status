(ns puppetlabs.trapperkeeper.services.status.status-proxy-service
  (:require [clojure.tools.logging :as log]
    [puppetlabs.trapperkeeper.core :refer [defservice]]))

(defservice status-proxy-service
  [[:WebroutingService add-proxy-route]
   [:ConfigService get-in-config]]
  (init [this context]
    (add-proxy-route
      this
      {:host (get-in-config [:status-proxy :target-host])
       :port (get-in-config [:status-proxy :target-port])
       :path (get-in-config [:status-proxy :target-url])}
      (get-in-config [:status-proxy :target-options]))
    (log/info "Initializing status service proxy")
    context))
