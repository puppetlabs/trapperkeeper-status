(ns puppetlabs.trapperkeeper.services.status.status-core
  (:require [schema.core :as schema]
            [compojure.core :as compojure]
            [ring.middleware.json :as ring-json]
            [puppetlabs.kitchensink.core :as ks]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def ServiceInfo
  {:service-version schema/Str
   :service-status-version schema/Int
   :status-fn (schema/make-fn-schema schema/Any schema/Any)})

(def ServicesInfo
  {schema/Str [ServiceInfo]})

(def ServiceStatus
  {:service-version schema/Str
   :service-status-version schema/Int
   :status schema/Any})

(def ServiceStatuses
  {schema/Str [ServiceStatus]})

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

(schema/defn call-status-fns-for-service :- [ServiceStatus]
  [service :- [ServiceInfo]]
  (for [{:keys [service-version
                service-status-version
                status-fn]} service]
    {:service-version service-version
     :service-status-version service-status-version
     :status (status-fn)}))

(schema/defn call-status-fns :- ServiceStatuses
  [context]
  (ks/mapvals call-status-fns-for-service (deref (:status-fns context))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Compojure App

(defn build-routes [context]
  (compojure/routes
    (compojure/context "/v1" []
      (compojure/GET "/services" []
        (let [statuses (call-status-fns context)]
          {:status 200
           :body {"services" statuses}})))))

(defn build-handler [context]
  (-> (build-routes context)
      ring-json/wrap-json-response))
