(ns puppetlabs.trapperkeeper.services.status.status-logging
  (:require [clojure.tools.logging :as log]
            [schema.core :as schema]
            [schema.utils :refer [validation-error-explain]]
            [cheshire.core :as json]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core])
  (:import (clojure.lang IFn)
           (java.util.concurrent Future)))

(schema/defn start-background-task :- Future
  "Creates a future which calls callback every frequency milliseconds"
  [frequency :- schema/Int
   callback :- IFn]
  (future (while true
            (callback)
            (Thread/sleep frequency))))

(defn logging-fn
  "Log status information at the debug level as json"
  []
  (let [status (status-core/v1-status :debug)]
    (log/info (json/generate-string status))))
