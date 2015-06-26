(ns puppetlabs.trapperkeeper.services.status.status-proxy-service
  (:require [clojure.tools.logging :as log]
    [puppetlabs.trapperkeeper.services.status.status-core :refer [StatusProxyConfig
                                                                  validate-protocol]]
    [puppetlabs.trapperkeeper.core :refer [defservice]]
    [schema.core :as schema])
  (:import (java.net URL)))

(defservice status-proxy-service
  [[:WebroutingService add-proxy-route]
   [:ConfigService get-in-config]]
  (init [this context]
    (log/info "Initializing status service proxy")
    (let [status-proxy-config (get-in-config [:status-proxy])]
      (schema/validate StatusProxyConfig status-proxy-config)
      (let [target-url (URL. (status-proxy-config :proxy-target-url))
            host (.getHost target-url)
            port (.getPort target-url)
            path (.getPath target-url)
            protocol (.getProtocol target-url)
            ssl-opts (status-proxy-config :ssl-opts)]
        (validate-protocol target-url)
        (add-proxy-route
          this
          {:host host
           :port port
           :path path}
          {:ssl-config ssl-opts
           :scheme     (keyword protocol)})))
    context))
