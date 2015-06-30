(ns puppetlabs.trapperkeeper.services.status.status-proxy-service
  (:require [clojure.tools.logging :as log]
    [puppetlabs.trapperkeeper.services.status.status-core :refer [get-proxy-route-info]]
    [puppetlabs.trapperkeeper.core :refer [defservice]]))

(defservice status-proxy-service
  [[:WebroutingService add-proxy-route]
   [:ConfigService get-in-config]]
  (init [this context]
    (log/info "Initializing status service proxy")
    (let [status-proxy-config (get-in-config [:status-proxy])
          {:keys [proxy-target proxy-options]} (get-proxy-route-info status-proxy-config)]
      (add-proxy-route this proxy-target proxy-options))
    context))
