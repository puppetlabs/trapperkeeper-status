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
   ;; TODO: actually specify inputs/outputs of callback function in this
   ;; schema
   :status-fn (schema/make-fn-schema schema/Any schema/Any)})

(def ServicesInfo
  {schema/Str [ServiceInfo]})

(def ServiceStatus
  {:service-version schema/Str
   :service-status-version schema/Int
   :status schema/Any})

(def ServicesStatus
  {schema/Str ServiceStatus})

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

(schema/defn call-latest-status-fn-for-service :- ServiceStatus
  "Given a list of maps containing service information and a status function,
  find the latest version, and return a map with the service's version, the
  version of the service's status, and the results of calling this status
  function."
  [service :- [ServiceInfo]]
  (let [latest-status (last (sort-by :service-status-version service))]
    (assoc (select-keys latest-status [:service-version :service-status-version])
           :status ((:status-fn latest-status)))))

(schema/defn call-status-fns :- ServicesStatus
  [status-fns-atom]
  "Call the latest status function for each service in the service context,
  and return a map of service to service status."
  (into {} (pmap (fn [[k v]] {k (call-latest-status-fn-for-service v)})
                 (deref status-fns-atom))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Compojure App

(defn build-routes
  [status-fns-atom]
  (compojure/routes
    (compojure/context "/v1" []
      (compojure/GET "/services" []
        (let [statuses (call-status-fns status-fns-atom)]
          {:status 200
           :body {"services" statuses}}))
       (compojure/GET "/services/:service-name" [service-name]
         (if-let [status-info (get (deref status-fns-atom) service-name)]
           {:status 200
            :body (call-latest-status-fn-for-service status-info)}
           {:status 404
            :body {:error {:type ::service-not-found
                           :message (str "No status information found for service "
                                         service-name)}}})))))

(defn build-handler [status-fns-atom]
  (-> (build-routes status-fns-atom)
      ring-json/wrap-json-response))
