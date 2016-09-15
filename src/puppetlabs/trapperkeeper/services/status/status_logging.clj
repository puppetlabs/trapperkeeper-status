(ns puppetlabs.trapperkeeper.services.status.status-logging
  (:require [clojure.tools.logging :as log]
            [schema.utils :refer [validation-error-explain]]
            [cheshire.core :as json]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]))

(defn log-status
  "Log status information at the debug level as json"
  []
  (let [status (status-core/v1-status :debug)]
    (log/debug (json/generate-string status))))
