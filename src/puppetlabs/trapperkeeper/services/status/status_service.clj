(ns puppetlabs.trapperkeeper.services.status.status-service
  (:require [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [service-context]]
            [puppetlabs.trapperkeeper.services.status.status-core :as core]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting-service]))

(defprotocol StatusService
  (register-status [this service-name service-version status-version status-fn]
    "Register a status callback function for a service by adding it to the
    status service context."))

(defservice status-service
  StatusService
  [[:WebroutingService add-ring-handler]]

  (init [this context]
    (assoc context :status-fns (atom {})))

  (start [this context]
    (log/info "Registering status service HTTP API at /status")
    (let [handler (core/build-handler context)]
      (add-ring-handler this (compojure/context "/status" [] handler)))
    context)

  (register-status [this service-name service-version status-version status-fn]
    (log/infof "Registering status callback function for %s service" service-name)
    (core/update-status-context (:status-fns (service-context this))
                                service-name service-version status-version status-fn)))
