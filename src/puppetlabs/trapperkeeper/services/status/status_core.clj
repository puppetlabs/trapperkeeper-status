(ns puppetlabs.trapperkeeper.services.status.status-core
  (:require [schema.core :as schema]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def ServiceInfo
  {:service-version schema/Str
   :service-status-version schema/Int
   :status-fn (schema/make-fn-schema schema/Any schema/Any)})

(def ServicesInfo
  {schema/Str [ServiceInfo]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn service-status-map :- ServiceInfo
  [svc-version status-version status-fn]
  {:service-version svc-version
   :service-status-version status-version
   :status-fn status-fn})

(defn update-status-context
  "Update the :status-fns atom in the service context."
  [status-fns-atom svc-name svc-version status-version status-fn]
  (let [status-map (service-status-map svc-version status-version status-fn)]
    (swap! status-fns-atom update-in [svc-name] conj status-map)))
