(ns puppetlabs.trapperkeeper.services.status.status-service
  (:require [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [service-context]]
            [puppetlabs.trapperkeeper.services.status.status-core :as core]))

(defprotocol StatusService
  (register-status [this service-name service-version status-version status-fn]
    "Register a status callback function for a service by adding it to the
    status service context.  status-fn must be a function of arity 1 which takes
    the status level as a keyword and returns the status information for
    the given level.  The return value of the callback function must satisfy
    the puppetlabs.trapperkeeper.services.status.status-core/StatusCallbackResponse
    schema."))

(defservice status-service
  StatusService
  [[:WebroutingService add-ring-handler get-route]]

  (init [this context]
    (assoc context :status-fns (atom {})))

  (start [this context]
    (log/info "Registering status service HTTP API at /status")
    (let [path (get-route this)
          handler (core/build-handler path (deref (:status-fns context)))]
      (add-ring-handler this handler))
    context)

  (register-status [this service-name service-version status-version status-fn]
    (log/infof "Registering status callback function for %s service" service-name)
    (core/update-status-context (:status-fns (service-context this))
      service-name service-version status-version status-fn)))
