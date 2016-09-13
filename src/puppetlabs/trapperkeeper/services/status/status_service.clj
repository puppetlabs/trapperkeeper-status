(ns puppetlabs.trapperkeeper.services.status.status-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [service-context]]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.trapperkeeper.services.status.status-logging :as status-logging]
            [schema.core :as schema]))

(defprotocol StatusService
  (register-status [this service-name service-version status-version status-fn]
    "Register a status callback function for a service by adding it to the
    status service context.  status-fn must be a function of arity 1 which takes
    the status level as a keyword and returns the status information for
    the given level.  The return value of the callback function must satisfy
    the puppetlabs.trapperkeeper.services.status.status-core/StatusCallbackResponse
    schema.")
  (get-status
    [this service-name level status-version]
    [this service-name level status-version timeout]
    "Call the status function for a registered service, optionally providing
    a timeout to override the default timeout value for the level."))

(defservice status-service
  StatusService
  [[:WebroutingService add-ring-handler get-route]
   [:ConfigService get-in-config]]

  (init [this context]
    (assoc context :status-fns (atom {})))

  (start [this context]
    (register-status this status-core/status-service-name
                     status-core/status-service-version
                     1
                     (partial status-core/v1-status))
    (log/info "Registering status service HTTP API at /status")
    (let [path (get-route this)
          handler (status-core/build-handler path (deref (:status-fns context)))]
      (add-ring-handler this handler))
    (let [config (schema/validate status-core/StatusConfig (get-in-config [:status]))
          status-logging-enabled? (:status-logging-enabled config)
          logging-frequency (:logging-frequency config)]
      (if status-logging-enabled?
        (do (log/info "Starting background logging of status data")
            (let [status-logging-future (status-logging/start-background-task
                                         logging-frequency
                                         status-logging/logging-fn)]
              (assoc context :status-logging-future status-logging-future)))
        context)))

  (stop [this context]
    (status-core/reset-status-context! (:status-fns context))
    ; Ensure background status logging has stopped
    (when-let [logging-future (:status-logging-future context)]
      (log/info "Stopping background logging of status data")
      (future-cancel logging-future))
    context)

  (register-status [this service-name service-version status-version status-fn]
    (log/infof "Registering status callback function for %s service" service-name)
    (status-core/update-status-context (:status-fns (service-context this))
                                       service-name service-version status-version status-fn))

  (get-status [this service-name level status-version]
    (get-status this service-name level status-version (status-core/check-timeout level)))

  (get-status [this service-name level status-version timeout]
    (let [status-fn (status-core/get-status-fn (:status-fns (service-context this)) service-name status-version)]
      (status-core/guarded-status-fn-call status-fn level timeout))))
