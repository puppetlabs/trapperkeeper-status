(ns puppetlabs.trapperkeeper.services.status.status-core
  (:require [schema.core :as schema]
            [schema.utils :refer [validation-error-explain]]
            [ring.middleware.defaults :as ring-defaults]
            [slingshot.slingshot :refer [throw+]]
            [compojure.core :as compojure]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.services.status.ringutils :as ringutils]
            [clj-semver.core :as semver]
            [trptcolin.versioneer.core :as versioneer])
  (:import (java.net URL)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def ServiceStatusDetailLevel
  (schema/enum :critical :info :debug))

(def State
  (schema/enum :running :error :unknown))

(def StatusCallbackResponse
  {:state State
   :status schema/Any})

(def StatusFn (schema/make-fn-schema StatusCallbackResponse ServiceStatusDetailLevel))

(def ServiceInfo
  {:service-version schema/Str
   :service-status-version schema/Int
   ;; Note that while this specifies the input and output for the status
   ;; function for each service, it does not actually validate these
   :status-fn StatusFn})

(def ServicesInfo
  {schema/Str [ServiceInfo]})

;; this is what gets returned in the HTTP response as json, and thus uses
;; underscores rather than hyphens
;; TODO: merge StatusCallbackResponse with this, rather than duplicating its
;; two keys, and remove underscores from this schema.
(def ServiceStatus
  {:service_version schema/Str
   :service_status_version schema/Int
   :state State
   :detail_level ServiceStatusDetailLevel
   :status schema/Any})

(def ServicesStatus
  {schema/Str ServiceStatus})

(def SemVerVersion
  (schema/pred semver/valid-format? "semver"))

(def StatusProxyConfig
  {:proxy-target-url schema/Str
   :ssl-opts         {:ssl-cert    schema/Str
                      :ssl-key     schema/Str
                      :ssl-ca-cert schema/Str}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defmacro with-timeout [timeout-s default & body]
  `(let [f# (future (do ~@body))
         result# (deref f# (* 1000 ~timeout-s) ~default)]
     (future-cancel f#)
     result#))

(defn- maybe-explain
  "Given the result of a call to schema.core/check, potentially unwrap it with
  validation-error-explain if it is a ValidationError object. Otherwise, pass
  the argument through."
  [schema-failure]
  (if (instance? schema.utils.ValidationError schema-failure)
    (validation-error-explain schema-failure)
    schema-failure))

(schema/defn check-timeout :- schema/Int
  "Given a status level keyword, returns a number of seconds to use as a timeout
  when calling a status function."
  [level :- ServiceStatusDetailLevel]
  (case level
    :critical 5
    :info 60
    :debug 60))

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

(defn validate-protocol!
  "Throws if the protocol is not http or https"
  [url]
  (let [protocol (.getProtocol url)
        url-string (str url)]
    (if-not (contains? #{"http" "https"} protocol)
      (throw (IllegalArgumentException.
               (format
                 (str "The proxy-target-url '%s' has an unsupported "
                   "protocol '%s'. Must be either http or https")
                 url-string
                 protocol))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate nominal? :- schema/Bool
  [status :- ServiceStatus]
  (= (:state status) :running))

(schema/defn ^:always-validate all-nominal? :- schema/Bool
  [statuses :- ServicesStatus]
  (every? nominal? (vals statuses)))

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

(schema/defn ^:always-validate guarded-status-fn-call :- StatusCallbackResponse
  "Given a status check function, a status detail level, and a timeout in
  seconds, this function calls the status function and handles three types of
  errors:

  * Status check timed out
  * Status check threw an Exception
  * Status check returned a form that doesn't match the StatusCallbackResponse schema

  In each error case, :state is set to :unknown and :status is set to a
  string describing the error."
  [status-fn :- StatusFn
   level :- ServiceStatusDetailLevel
   timeout :- schema/Int]
   (let [unknown-response (fn [status] {:state :unknown
                                        :status status})
         timeout-response (unknown-response (format "Status check timed out after %s seconds" timeout))]
     (with-timeout timeout timeout-response
       (try
         (let [status (status-fn level)]
           (if-let [schema-failure (schema/check StatusCallbackResponse status)]
             (unknown-response (format "Status check malformed: %s" (maybe-explain schema-failure)))
             status))
         (catch Exception e
           (unknown-response (format "Status check threw an exception: %s" e)))))))

(schema/defn ^:always-validate call-status-fn-for-service :- ServiceStatus
  "Construct a map with the service's version, the version of the service's
  status, the detail level, and the results of calling the status function
  corresponding to the status version specified (or the most recent version if
  not). If the response from the callback function does not include an
  :state key, or returns a value other than true or false, return
  :unknown for :state."
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
     (let [timeout (check-timeout level)
           callback-resp (guarded-status-fn-call (:status-fn status) level timeout)
           data (:status callback-resp)
           state (if-not (schema/check State (:state callback-resp))
                   (:state callback-resp)
                   :unknown)]
       {:service_version (:service-version status)
        :service_status_version (:service-status-version status)
        :detail_level level
        :state state
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

(schema/defn ^:always-validate summarize-states :- State
  "Given a map of service statuses:
   * if all of the statuses have the same :state, returns that :state
   * if not all of the statuses are the same, returns :unknown"
  [statuses :- ServicesStatus]
  (let [state-set (->> statuses
                       vals
                       (map :state)
                       set)]
    (cond
      (state-set :error) :error
      (state-set :unknown) :unknown
      (state-set :running) :running
      :else :unknown)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Compojure App

(schema/defn ^:always-validate status->code :- schema/Int
  "Given a service status, returns an appropriate HTTP status code"
  [status :- ServiceStatus]
  (if (nominal? status) 200 503))

(schema/defn ^:always-validate statuses->code :- schema/Int
  "Given a map of service statuses, returns an appropriate HTTP status code."
  [statuses :- ServicesStatus]
  (if (all-nominal? statuses) 200 503))

(defn build-plaintext-routes
  [path status-fns]
  (comidi/context path
     (comidi/GET "" _
       (let [statuses (call-status-fns status-fns :critical)]
         (ringutils/plain-response (statuses->code statuses)
           (-> statuses
               summarize-states
               name))))

     (comidi/GET ["/" :service-name] [service-name]
       (if-let [service-info (get status-fns service-name)]
         (let [status (call-status-fn-for-service service-name
                                                  service-info
                                                  :critical)]
           (ringutils/plain-response (status->code status)
             (name (:state status))))
         (ringutils/plain-response 404
           (format "not found: %s" service-name))))))

(defn build-json-routes
  [path status-fns]
  (comidi/context path
      (comidi/GET "" [:as {params :params}]
        (let [level (get-status-detail-level params)
              statuses (call-status-fns status-fns level)]
          (ringutils/json-response (statuses->code statuses)
            statuses)))

      (comidi/GET ["/" :service-name] [service-name :as {params :params}]
        (if-let [service-info (get status-fns service-name)]
          (let [level (get-status-detail-level params)
                service-status-version (get-service-status-version params)
                status (call-status-fn-for-service service-name
                         service-info
                         level
                         service-status-version)]
            (ringutils/json-response (status->code status)
              (assoc status :service_name service-name)))
          ;; else (no service with that name)
          (ringutils/json-response 404
             {:type :service-not-found
              :message (str "No status information found for service "
                            service-name)})))))

(schema/defn ^:always-validate wrap-errors-by-type
  [handler t :- ringutils/ResponseType]
  (-> handler
      (ringutils/wrap-request-data-errors t)
      (ringutils/wrap-schema-errors t)
      (ringutils/wrap-errors t)))

(defn build-handler [path status-fns]
  (-> (compojure/context path []
        (compojure/context "/v1" []
          (compojure/routes
            (-> (build-json-routes "/services" status-fns)
                comidi/routes->handler
                (wrap-errors-by-type :json))
            (-> (build-plaintext-routes "/simple" status-fns)
                comidi/routes->handler
                (wrap-errors-by-type :plain)))))
      (ring-defaults/wrap-defaults ring-defaults/api-defaults)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Status Proxy
(schema/defn ^:always-validate get-proxy-route-info
  "Validates the status-proxy-config and returns a map with parameters to be
  used with add-proxy-route:
    proxy-target: target host, port, and path
    proxy-options: SSL options for the proxy target"
  [status-proxy-config :- StatusProxyConfig]
  (let [target-url (URL. (status-proxy-config :proxy-target-url))
        host (.getHost target-url)
        port (.getPort target-url)
        path (.getPath target-url)
        protocol (.getProtocol target-url)
        ssl-opts (status-proxy-config :ssl-opts)]
    (validate-protocol! target-url)
    {:proxy-target {:host host
                    :port port
                    :path path}
     :proxy-options {:ssl-config ssl-opts
                     :scheme (keyword protocol)}}))

