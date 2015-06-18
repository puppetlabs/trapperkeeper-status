(ns puppetlabs.trapperkeeper.services.status.status-core
  (:require [schema.core :as schema]
            [ring.middleware.json :as ring-json]
            [ring.middleware.defaults :as ring-defaults]
            [slingshot.slingshot :refer [throw+]]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.services.status.ringutils :as ringutils]
            [clj-semver.core :as semver]
            [trptcolin.versioneer.core :as versioneer]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def ServiceStatusDetailLevel
  (schema/enum :critical :info :debug))

(def IsRunning
  (schema/enum :true :false :unknown))

(def StatusCallbackResponse
  {:is-running IsRunning
   :status schema/Any})

(def ServiceInfo
  {:service-version schema/Str
   :service-status-version schema/Int
   ;; Note that while this specifies the input and output for the status
   ;; function for each service, it does not actually validate these
   :status-fn (schema/make-fn-schema StatusCallbackResponse ServiceStatusDetailLevel)})

(def ServicesInfo
  {schema/Str [ServiceInfo]})

;; this is what gets returned in the HTTP response as json, and thus uses
;; underscores rather than hyphens
;; TODO: merge StatusCallbackResponse with this, rather than duplicating its
;; two keys, and remove underscores from this schema.
(def ServiceStatus
  {:service_version schema/Str
   :service_status_version schema/Int
   :is_running IsRunning
   :detail_level ServiceStatusDetailLevel
   :status schema/Any})

(def ServicesStatus
  {schema/Str ServiceStatus})

(def SemVerVersion
  (schema/pred semver/valid-format? "semver"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn validate-callback-registration
  [status-fns svc-name svc-version status-version]
  (let [differing-svc-version? (and (not (nil? (first status-fns)))
                                 (not= (:service-version (first status-fns))
                                   svc-version))
        differing-status-version? (not (empty?
                                         (filter #(= (:service-status-version %)
                                                   status-version)
                                           status-fns)))
        error-message (if differing-svc-version?
                        (str "Cannot register multiple callbacks for a single "
                          "service with different service versions.")
                        (str "Service function already exists for service "
                          svc-name
                          " with status version "
                          status-version))]
    (when (or differing-svc-version? differing-status-version?)
      (throw (IllegalStateException. error-message)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  get-artifact-version :- SemVerVersion
  "Utility function that services can use to get a value to pass in as their
  `service-version` when registering a status callback.  `group-id` and
  `artifact-id` should match the maven/leiningen identifiers for the project
  that the service is defined in."
  [group-id artifact-id]
  (let [version (versioneer/get-version group-id artifact-id)]
    (when (empty? version)
      (throw (IllegalStateException.
               (format "Unable to find version number for '%s/%s'"
                 group-id
                 artifact-id))))
    (when-not (semver/valid-format? version)
      (throw (IllegalStateException.
               (format "Service '%s/%s' has version that does not comply with semver: '%s'"
                 group-id
                 artifact-id
                 version))))
    version))

(defn level->int
  "Returns an integer which represents the given status level.
   The ordering of levels is :critical < :info < :debug."
  [level]
  (case level
    :critical 0
    :info 1
    :debug 2))

(defn compare-levels
  "Converts the two status levels to integers using level->int and then
   invokes f, passing the two integers as arguments.  Especially useful for
   comparing two status levels."
  [f level1 level2]
  (f (level->int level1) (level->int level2)))

(schema/defn service-status-map :- ServiceInfo
  [svc-version status-version status-fn]
  {:service-version svc-version
   :service-status-version status-version
   :status-fn status-fn})

(defn update-status-context
  "Update the :status-fns atom in the service context."
  [status-fns-atom svc-name svc-version status-version status-fn]
  (validate-callback-registration (get (deref status-fns-atom) svc-name)
    svc-name
    svc-version
    status-version)
  (let [status-map (service-status-map svc-version status-version status-fn)]
    (swap! status-fns-atom update-in [svc-name] conj status-map)))

(schema/defn ^:always-validate call-status-fn-for-service :- ServiceStatus
  "Construct a map with the service's version, the version of the service's
  status, the detail level, and the results of calling the status function
  corresponding to the status version specified (or the most recent version if
  not). If the response from the callback function does not include an
  :is-running key, or returns a value other than true or false, return
  :unknown for :is-running."
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
            is-running (if-not (schema/check IsRunning (:is-running callback-resp))
                         (:is-running callback-resp)
                         :unknown)]
        {:service_version (:service-version status)
         :service_status_version (:service-status-version status)
         :detail_level level
         :is_running is-running
         :status data}))))

(schema/defn ^:always-validate call-status-fns :- ServicesStatus
  "Call the latest status function for each service in the service context,
  and return a map of service to service status."
  [status-fns :- ServicesInfo
   level :- ServiceStatusDetailLevel]
  (try
    (into {} (pmap (fn [[k v]] {k (call-status-fn-for-service k v level)})
               status-fns))
    ;; pmap returns all exceptions that occur while it is executing tasks in a
    ;; java.util.concurrent.ExecutionException. This unwraps and rethrows
    ;; these exceptions so that our other middleware can handle them
    ;; appropriately.
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
  (when-let [level (params :service_status_version)]
    (if-let [parsed-level (ks/parse-int level)]
      parsed-level
      (throw+ {:type    :request-data-invalid
               :message (str "Invalid service_status_version. Should be an "
                          "integer but was " level)}))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Compojure App

(defn build-routes
  [path status-fns]
  (comidi/context path
    (comidi/context "/v1"
      (comidi/GET "/services" [:as {params :params}]
        (let [level (get-status-detail-level params)
              statuses (call-status-fns status-fns level)]
          {:status 200
           :body statuses}))
      (comidi/GET ["/services/" :service-name] [service-name :as {params :params}]
        (if-let [service-info (get status-fns service-name)]
          (let [level (get-status-detail-level params)
                service-status-version (get-service-status-version params)
                status (call-status-fn-for-service service-name
                         service-info
                         level
                         service-status-version)]
            {:status 200
             :body (assoc status :service_name service-name)})
          ;; else (no service with that name)
          {:status 404
           :body {:type :service-not-found
                  :message (str "No status information found for service "
                             service-name)}})))))

(defn build-handler [path status-fns]
  (-> (build-routes path status-fns)
    comidi/routes->handler
    ringutils/wrap-request-data-errors
    ringutils/wrap-schema-errors
    ringutils/wrap-errors
    ring-json/wrap-json-response
    (ring-defaults/wrap-defaults ring-defaults/api-defaults)))
