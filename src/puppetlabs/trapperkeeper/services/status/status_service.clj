(ns puppetlabs.trapperkeeper.services.status.status-service
  (:require [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [service-context]]
            [puppetlabs.trapperkeeper.services.status.status-core :as core]))

(defprotocol StatusService
  (register-status [this service-name service-version status-version status-fn]
    "Register a status callback function for a service by adding it to the
    status service context."))

(defservice status-service
  StatusService
  [[:WebroutingService add-ring-handler get-route]]

  (init [this context]
    (assoc context :status-fns (atom {})))

  (start [this context]
    (log/info "Registering status service HTTP API at /status")
    (let [path (get-route this)
          handler (core/build-handler (:status-fns context))]
      (add-ring-handler this (compojure/context path [] handler)))
    context)

  (register-status [this service-name service-version status-version status-fn]
    (log/infof "Registering status callback function for %s service" service-name)
    (core/update-status-context (:status-fns (service-context this))
                                service-name service-version status-version status-fn)))
