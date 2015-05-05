(ns puppetlabs.trapperkeeper.services.status.status-core
  (:require [schema.core :as schema]
            [compojure.core :as compojure]
            [compojure.handler :as handler]
            [ring.middleware.json :as ring-json]
            [slingshot.slingshot :refer [throw+]]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.services.status.ringutils :as ringutils]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def ServiceStatusDetailLevel
  (schema/enum :critical :info :debug))

(def ServiceInfo
  {:service-version schema/Str
   :service-status-version schema/Int
   ;; TODO: specify output of callback function in this schema?
   :status-fn (schema/make-fn-schema ServiceStatusDetailLevel schema/Any)})

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

(schema/defn ^:always-validate call-status-fn-for-service :- ServiceStatus
  "Given a list of maps containing service information and a status function,
  find the specified version, and return a map with the service's version, the
  version of the service's status, and the results of calling this status
  function. If no version for the status function is specified, the most recent
  version will be used."
  ([service-name :- schema/Str
    service :- [ServiceInfo]
    level :- ServiceStatusDetailLevel]
    (call-status-fn-for-service service-name service level nil))
  ([service-name :- schema/Str
    service :- [ServiceInfo]
    level :- ServiceStatusDetailLevel
    service-status-version :- (schema/maybe schema/Int)]
    (let [status (if (nil? service-status-version)
                   (last (sort-by :service-status-version service))
                   (first (filter #(= (:service-status-version %)
                                      service-status-version)
                                  service)))]
      (when (nil? status)
        (throw+ {:type    :service-status-version-not-found
                 :message (str "No status function with version "
                               service-status-version
                               " found for service "
                               service-name)}))
      (assoc (select-keys status [:service-version :service-status-version])
        :status ((:status-fn status) level)))))

(schema/defn ^:always-validate call-status-fns :- ServicesStatus
  "Call the latest status function for each service in the service context,
  and return a map of service to service status."
  [status-fns :- ServicesInfo
   level :- ServiceStatusDetailLevel]
  (into {} (pmap (fn [[k v]] {k (call-status-fn-for-service k v level)})
                 status-fns)))

(defn get-status-detail-level
  "Given a params map from a request, get out the status level and check
  whether it is valid. If not, throw an error. If no status level was in the
  params, then default to 'info'."
  [params]
  (if-let [level (keyword (params :level))]
    (if-not (schema/check ServiceStatusDetailLevel level)
      level
      (throw+  {:type :request-data-invalid
                :message (str "Invalid level: " level)}))
    :info))

(defn get-service-status-version
  "Given a params map from a request, get out the service status version and
   check whether it is valid. If not, throw an error."
  [params]
  (when-let [level (params :service-status-version)]
    (if-let [parsed-level (ks/parse-int level)]
      parsed-level
      (throw+ {:type    :request-data-invalid
               :message (str "Invalid service-status-version. Should be an "
                             "integer but was " level)}))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Compojure App

(defn build-routes
  [status-fns-atom]
  (handler/api
    (compojure/routes
      (compojure/context "/v1" []
        (compojure/GET "/services" [:as {params :params}]
          (let [level (get-status-detail-level params)
                statuses (call-status-fns (deref status-fns-atom)
                                          level)]
            {:status 200
             :body statuses}))
         (compojure/GET "/services/:service-name" [service-name :as {params :params}]
           (if-let [service-info (get (deref status-fns-atom) service-name)]
             (let [level (get-status-detail-level params)
                   service-status-version (get-service-status-version params)
                   status (call-status-fn-for-service service-name
                                                      service-info
                                                      level
                                                      service-status-version)]
               {:status 200
                :body (assoc status :service-name service-name)})
             {:status 404
              :body {:error {:type :service-not-found
                             :message (str "No status information found for service "
                                           service-name)}}}))))))

(defn build-handler [status-fns-atom]
  (-> (build-routes status-fns-atom)
      ringutils/wrap-request-data-errors
      ringutils/wrap-schema-errors
      ringutils/wrap-errors
      ring-json/wrap-json-response))
