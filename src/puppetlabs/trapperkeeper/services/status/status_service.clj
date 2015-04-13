(ns puppetlabs.trapperkeeper.services.status.status-service
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [service-context]]
            [puppetlabs.trapperkeeper.services.status.status-core :as core]))

(defprotocol StatusService
  (register-status [this service-name service-version status-version status-fn]
    "Register a status callback function for a service by adding it to the
    status service context."))

(defservice status-service
  StatusService
  []

  (init [this context]
    (assoc context :status-fns (atom {})))

  (register-status [this service-name service-version status-version status-fn]
    (log/infof "Registering status callback function for %s service" service-name)
    (core/update-status-context (:status-fns (service-context this))
                                service-name service-version status-version status-fn)))
