(ns puppetlabs.trapperkeeper.services.status.status-debug-logging
  (:require [clojure.tools.logging :as log]
            [schema.utils :refer [validation-error-explain]]
            [cheshire.core :as json]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]))
(defn log-status
  "Log status information at the debug level as json

  Note: This function is in its own namespace so that logback can use the namespace
  as a way to route these log messages separately from other logging the application
  might be doing, and so it shouldn't be moved from this namespace"
  []
  (let [status (status-core/status-latest-version :debug)]
    (log/debug (json/generate-string status))))
