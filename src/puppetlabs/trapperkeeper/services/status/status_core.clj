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

(def StatusCallbackResponse
  {:is-running schema/Bool
   :status schema/Any})

(def ServiceInfo
  {:service-version schema/Str
   :service-status-version schema/Int
   ;; Note that while this specifies the input and output for the status
   ;; function for each service, it does not actually validate these
   :status-fn (schema/make-fn-schema StatusCallbackResponse ServiceStatusDetailLevel)})

(def ServicesInfo
  {schema/Str [ServiceInfo]})

(def ServiceStatus
  {:service-version schema/Str
   :service-status-version schema/Int
   :is-running (schema/enum true false :unknown)
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
      (let [callback-resp ((:status-fn status) level)
            data (:status callback-resp)
            is-running (if-not (schema/check schema/Bool (:is-running callback-resp))
                         (:is-running callback-resp)
                         :unknown)
            versions (select-keys status [:service-version :service-status-version])]
        (assoc versions :status data :is-running is-running)))))

(schema/defn ^:always-validate call-status-fns :- ServicesStatus
  "Call the latest status function for each service in the service context,
  and return a map of service to service status. Unwrap and rethrow exceptions
  from pmap that are within a java.util.concurrent.ExecutionException."
  [status-fns :- ServicesInfo
   level :- ServiceStatusDetailLevel]
  (try
    (into {} (pmap (fn [[k v]] {k (call-status-fn-for-service k v level)})
                   status-fns))
    (catch java.util.concurrent.ExecutionException e
      (throw (.getCause e)))))

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
  [status-fns]
  (handler/api
    (compojure/routes
      (compojure/context "/v1" []
        (compojure/GET "/services" [:as {params :params}]
          (let [level (get-status-detail-level params)
                statuses (call-status-fns status-fns level)]
            {:status 200
             :body statuses}))
         (compojure/GET "/services/:service-name" [service-name :as {params :params}]
           (if-let [service-info (get status-fns service-name)]
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

(defn build-handler [status-fns]
  (-> (build-routes status-fns)
      ringutils/wrap-request-data-errors
      ringutils/wrap-schema-errors
      ringutils/wrap-errors
      ring-json/wrap-json-response))
