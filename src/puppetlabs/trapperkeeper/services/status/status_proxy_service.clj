(ns puppetlabs.trapperkeeper.services.status.status-proxy-service
  (:require [clojure.tools.logging :as log]
    [puppetlabs.trapperkeeper.core :refer [defservice]])
  (:import (java.net URL)))

(defservice status-proxy-service
  [[:WebroutingService add-proxy-route]
   [:ConfigService get-in-config]]
  (init [this context]
    (let [target-url (URL. (get-in-config [:status-proxy :proxy-target-url]))
          host (.getHost target-url)
          port (.getPort target-url)
          path (.getPath target-url)]
      (add-proxy-route
        this
        {:host host
         :port port
         :path path}
        {:ssl-config (get-in-config [:status-proxy :ssl-opts])
         :scheme     :https}))
    (log/info "Initializing status service proxy")
    context))
