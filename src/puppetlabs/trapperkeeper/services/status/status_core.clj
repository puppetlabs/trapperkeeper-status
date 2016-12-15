(ns puppetlabs.trapperkeeper.services.status.status-core
  (:require [clojure.tools.logging :as log]
            [clojure.set :as setutils]
            [schema.core :as schema]
            [schema.utils :refer [validation-error-explain]]
            [ring.middleware.defaults :as ring-defaults]
            [slingshot.slingshot :refer [throw+]]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.ring-middleware.utils :as ringutils]
            [puppetlabs.ring-middleware.core :as middleware]
            [puppetlabs.trapperkeeper.services.status.cpu-monitor :as cpu]
            [trptcolin.versioneer.core :as versioneer]
            [clojure.java.jmx :as jmx]
            [puppetlabs.i18n.core :as i18n])
  (:import (java.net URL)
           (java.util.concurrent CancellationException)
           (java.lang.management ManagementFactory)
           (clojure.lang IFn)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def WholeSeconds schema/Int)
(def WholeMilliseconds schema/Int)

(def ServiceStatusDetailLevel
  (schema/enum :critical :info :debug))

(def State
  (schema/enum :running :error :starting :stopping :unknown))

(def Alert
  {:severity (schema/enum :error :warning :info)
   :message schema/Str})

(def StatusCallbackResponse
  {:state State
   :status schema/Any
   (schema/optional-key :alerts) [Alert]})

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
(def ServiceStatus
  {:service_version schema/Str
   :service_status_version schema/Int
   :state State
   :detail_level ServiceStatusDetailLevel
   :status schema/Any
   :active_alerts [Alert]})

(def ServicesStatus
  {schema/Str ServiceStatus})

(def Version schema/Str)

(def StatusProxyConfig
  {:proxy-target-url schema/Str
   :ssl-opts         {:ssl-cert    schema/Str
                      :ssl-key     schema/Str
                      :ssl-ca-cert schema/Str}})

(def MemoryUsageV1
  {:committed schema/Int
   :init schema/Int
   :max schema/Int
   :used schema/Int})

(def FileDescriptorUsageV1
  {:max schema/Int
   :used schema/Int})

(def GcStatsV1
  {schema/Str {:count schema/Int
               :total-time-ms schema/Int}})

(def JvmMetricsV1
  {:heap-memory MemoryUsageV1
   :non-heap-memory MemoryUsageV1
   :file-descriptors FileDescriptorUsageV1
   :gc-stats GcStatsV1
   :up-time-ms WholeMilliseconds
   :start-time-ms WholeMilliseconds
   :cpu-usage schema/Num
   :gc-cpu-usage schema/Num})

(def DebugLoggingConfig
  (schema/maybe {:interval-minutes schema/Num}))

(def StatusServiceConfig
  {(schema/optional-key :debug-logging) DebugLoggingConfig
   (schema/optional-key :cpu-metrics-interval-seconds) schema/Num})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defmacro with-timeout [description timeout-s default & body]
  `(let [f# (future (do ~@body))
         result# (deref f# (* 1000 ~timeout-s) ~default)]
     (future-cancel f#)
     (when (future-cancelled? f#)
       (log/error (i18n/trs "{0} timed out, shutting down background task"
                            ~description))
       (try @f#
            (catch CancellationException e#
              (log/error e#))))
     result#))

(defn- maybe-explain
  "Given the result of a call to schema.core/check, potentially unwrap it with
  validation-error-explain if it is a ValidationError object. Otherwise, pass
  the argument through."
  [schema-failure]
  (if (instance? schema.utils.ValidationError schema-failure)
    (validation-error-explain schema-failure)
    schema-failure))

(schema/defn check-timeout :- WholeSeconds
  "Given a status level keyword, returns an integral number of seconds to use as
  a timeout when calling a status function."
  [level :- ServiceStatusDetailLevel]
  (case level
    :critical 30
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
                        (i18n/tru "Cannot register multiple callbacks for a single service with different service versions.")
                        (i18n/tru "Service function already exists for service {0} with status version {1}" svc-name status-version))]
    (when (or differing-svc-version? differing-status-version?)
      (throw (IllegalStateException. error-message)))))

(defn validate-protocol!
  "Throws if the protocol is not http or https"
  [url]
  (let [protocol (.getProtocol url)
        url-string (str url)]
    (if-not (contains? #{"http" "https"} protocol)
      (throw (IllegalArgumentException.
              (i18n/tru "The proxy-target-url ''{0}'' has an unsupported protocol ''{1}''. Must be either http or https"
                        url-string
                        protocol))))))

(schema/defn ^:always-validate get-jvm-metrics :- JvmMetricsV1
  [cpu-snapshot :- cpu/CpuUsageSnapshot]
  (let [runtime-bean (ManagementFactory/getRuntimeMXBean)
        gc-beans (jmx/mbean-names "java.lang:name=*,type=GarbageCollector")]
    {:heap-memory (jmx/read "java.lang:type=Memory" :HeapMemoryUsage)
     :non-heap-memory (jmx/read "java.lang:type=Memory" :NonHeapMemoryUsage)
     :file-descriptors (setutils/rename-keys
                        (jmx/read "java.lang:type=OperatingSystem" [:OpenFileDescriptorCount :MaxFileDescriptorCount])
                        {:OpenFileDescriptorCount :used :MaxFileDescriptorCount :max})
     :gc-stats (into {} (for [gc gc-beans]
                          (let [gc-name (.getKeyProperty gc "name")
                                gc-info (setutils/rename-keys
                                         (jmx/read gc [:CollectionCount :CollectionTime])
                                         {:CollectionCount :count :CollectionTime :total-time-ms})]
                            {gc-name gc-info})))
     :cpu-usage (:cpu-usage cpu-snapshot)
     :gc-cpu-usage (:gc-cpu-usage cpu-snapshot)
     :up-time-ms (.getUptime runtime-bean)
     :start-time-ms (.getStartTime runtime-bean)}))

(schema/defn update-cpu-usage-metrics
  [last-cpu-snapshot :- (schema/atom cpu/CpuUsageSnapshot)]
  (swap! last-cpu-snapshot cpu/get-cpu-values))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(def status-service-name "status-service")

(schema/defn ^:always-validate validate-config :- StatusServiceConfig
  [config]
  (let [config (or config {})]
    (schema/validate DebugLoggingConfig (:debug-logging config))
    config))

(schema/defn ^:always-validate schedule-bg-tasks
  [interspaced :- IFn
   log-status :- IFn
   config :- StatusServiceConfig
   last-cpu-snapshot :- (schema/atom cpu/CpuUsageSnapshot)]
  (let [interval-minutes (get-in config [:debug-logging :interval-minutes])]
    (when interval-minutes
      (let [interval-milliseconds (* 60000 interval-minutes)]
        (log/info "Starting background logging of status data")
        (interspaced interval-milliseconds log-status))))
  (let [cpu-metrics-interval-seconds (get-in config [:cpu-metrics-interval-seconds] 5)]
    (when (pos? cpu-metrics-interval-seconds)
      (log/info "Starting background monitoring of cpu usage metrics")
      (interspaced (* cpu-metrics-interval-seconds 1000)
                   (partial update-cpu-usage-metrics last-cpu-snapshot)))))

(schema/defn ^:always-validate nominal? :- schema/Bool
  [status :- ServiceStatus]
  (= (:state status) :running))

(schema/defn ^:always-validate all-nominal? :- schema/Bool
  [statuses :- ServicesStatus]
  (every? nominal? (vals statuses)))

(schema/defn ^:always-validate
  get-artifact-version :- schema/Str
  "Utility function that services can use to get a value to pass in as their
  `service-version` when registering a status callback.  `group-id` and
  `artifact-id` should match the maven/leiningen identifiers for the project
  that the service is defined in."
  [group-id artifact-id]
  (let [version (versioneer/get-version group-id artifact-id)]
    (when (empty? version)
      (throw (IllegalStateException.
               (i18n/tru "Unable to find version number for ''{0}/{1}''"
                 group-id
                 artifact-id))))
    version))

(def status-service-version
  (get-artifact-version "puppetlabs" "trapperkeeper-status"))

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

(schema/defn ^:always-validate reset-status-context! :- nil
  "Remove a key from the :status-fns atom in the service context"
  [status-fns-atom :- clojure.lang.Atom]
  (reset! status-fns-atom {})
  nil)

(schema/defn ^:always-validate guarded-status-fn-call :- StatusCallbackResponse
  "Given a status check function, a status detail level, and a timeout in
  (integral) seconds, this function calls the status function and handles three
  types of errors:

  * Status check timed out
  * Status check threw an Exception
  * Status check returned a form that doesn't match the StatusCallbackResponse schema

  In each error case, :state is set to :unknown and :status is set to a
  string describing the error."
  [status-fn :- StatusFn
   level :- ServiceStatusDetailLevel
   timeout :- WholeSeconds]
   (let [unknown-response (fn [status] {:state :unknown
                                        :status status})
         timeout-response (unknown-response (format "Status check timed out after %s seconds" timeout))]
     (with-timeout "Status callback" timeout timeout-response
       (try
         (let [status (status-fn level)]
           (if-let [schema-failure (schema/check StatusCallbackResponse status)]
             (unknown-response (i18n/tru "Status check malformed: {0}" (maybe-explain schema-failure)))
             status))
         (catch InterruptedException e
           ;; if we get here it's almost certainly because the timeout was reached,
           ;; so the macro already has a return value and we don't need to bother
           ;; returning one
           (log/error e (i18n/trs "Status callback interrupted")))
         (catch Exception e
           (let [error-msg (i18n/trs "Status check threw an exception")]
             (log/error e error-msg)
             (unknown-response (format "%s: %s" error-msg e))))))))

(schema/defn ^:always-validate matching-service-info :- ServiceInfo
  "Find a service info entry matching the service-status-version. If
  service-status-version is nil the most recent service info is returned."
  [service-name :- schema/Str
   service :- [ServiceInfo]
   service-status-version :- (schema/maybe schema/Int)]
   (let [status (if (nil? service-status-version)
                  (last (sort-by :service-status-version service))
                  (first (filter #(= (:service-status-version %)
                                     service-status-version)
                                 service)))]
     (if (nil? status)
       (throw+ {:kind :service-status-version-not-found
                :msg (i18n/tru "No status function with version {0} found for service {1}" service-status-version service-name)})
       status)))

(schema/defn ^:always-validate get-status-fn :- StatusFn
  "Retrieve the status-fn for a service by name and optionally by service-status version.
  If service-status-version is nil the status fn for the most recent status version used."
  [services-info-atom
   service-name :- schema/Str
   service-status-version :- (schema/maybe schema/Int)]
  (let [service-info (-> services-info-atom deref (get service-name))]
     (if (nil? service-info)
       (throw+ {:kind :service-info-not-found
                :msg (i18n/tru "No service info found for service {0}" service-name)})
       (:status-fn (matching-service-info service-name service-info service-status-version)))))

(schema/defn ^:always-validate call-status-fn-for-service :- ServiceStatus
  "Construct a map with the service's version, the version of the service's
  status, the detail level, and the results of calling the status function
  corresponding to the status version specified (or the most recent version if
  not). If the response from the callback function does not include an
  :state key, or returns a value other than true or false, return
  :unknown for :state."
  ([service-name :- schema/Str
    service :- [ServiceInfo]
    level :- ServiceStatusDetailLevel
    timeout :- WholeSeconds]
    (call-status-fn-for-service service-name service level timeout nil))
  ([service-name :- schema/Str
    service :- [ServiceInfo]
    level :- ServiceStatusDetailLevel
    timeout :- WholeSeconds
    service-status-version :- (schema/maybe schema/Int)]
   (let [status (matching-service-info service-name service service-status-version)
         callback-resp (guarded-status-fn-call (:status-fn status) level timeout)
         data (:status callback-resp)
         state (if-not (schema/check State (:state callback-resp))
                 (:state callback-resp)
                 :unknown)
         alerts (get callback-resp :alerts [])]
       {:service_version (:service-version status)
        :service_status_version (:service-status-version status)
        :detail_level level
        :state state
        :status data
        :active_alerts alerts})))

(schema/defn ^:always-validate call-status-fns :- ServicesStatus
  "Call the latest status function for each service in the service context,
  and return a map of service to service status."
  [status-fns :- ServicesInfo
   level :- ServiceStatusDetailLevel
   timeout :- WholeSeconds]
  (try
    (into {} (pmap (fn [[k v]] {k (call-status-fn-for-service k v level timeout)})
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
      (ringutils/throw-data-invalid! (i18n/tru "Invalid level: {0}" level)))
    :info))

(defn get-service-status-version
  "Given a params map from a request, get out the service status version and
   check whether it is valid. If not, throw an error."
  [params]
  (when-let [version (params :service_status_version)]
    (if-let [numeric-version (ks/parse-int version)]
      numeric-version
      (ringutils/throw-data-invalid!
       (i18n/tru
        "Invalid service_status_version. Should be an integer but was {0}"
        version)))))

(defn get-timeout
  "Given a params map from a request, attempt to find the timeout parameter and
  parse it as an integer, returning the numeric value. If no timeout parameter
  is found, return nil. If the parameter isn't parseable as an integer, throw an
  exception."
  [params]
  (let [timeout-param (:timeout params)
        numeric-timeout (and timeout-param (ks/parse-int timeout-param))]
    (cond
      (nil? timeout-param) nil
      (nil? numeric-timeout) (ringutils/throw-data-invalid!
                              (i18n/tru
                               "Invalid timeout. Should be an integer but was {0}"
                               timeout-param))
      (<= numeric-timeout 0) (ringutils/throw-data-invalid!
                              (i18n/tru
                               "Invalid timeout. Timeout must be greater than zero but was {0}"
                               numeric-timeout))
      :else numeric-timeout)))

(schema/defn ^:always-validate summarize-states :- State
  "Given a map of service statuses, return the 'most severe' state present as
   ranked by :error, :unknown, :stopping, :starting, :running"
  [statuses :- ServicesStatus]
  (let [state-set (->> statuses
                       vals
                       (map :state)
                       set)]
    (cond
      (state-set :error) :error
      (state-set :unknown) :unknown
      (state-set :stopping) :stopping
      (state-set :starting) :starting
      (state-set :running) :running
      :else :unknown)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Comidi App

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
     (comidi/GET "" [:as {params :params}]
       (let [timeout (or (get-timeout params) (check-timeout :critical))
             statuses (call-status-fns status-fns :critical timeout)]
         (ringutils/plain-response (statuses->code statuses)
           (-> statuses
               summarize-states
               name))))

     (comidi/GET ["/" :service-name] [service-name :as {params :params}]
       (if-let [service-info (get status-fns service-name)]
         (let [timeout (or (get-timeout params) (check-timeout :critical))
               status (call-status-fn-for-service service-name
                                                  service-info
                                                  :critical
                                                  timeout)]
           (ringutils/plain-response (status->code status)
             (name (:state status))))
         (ringutils/plain-response 404
           (i18n/tru "not found: {0}" service-name))))))

(defn build-json-routes
  [path status-fns]
  (comidi/context path
      (comidi/GET "" [:as {params :params}]
        (let [level (get-status-detail-level params)
              timeout (or (get-timeout params) (check-timeout level))
              statuses (call-status-fns status-fns level timeout)]
          (ringutils/json-response (statuses->code statuses)
            statuses)))

      (comidi/GET ["/" :service-name] [service-name :as {params :params}]
        (if-let [service-info (get status-fns service-name)]
          (let [level (get-status-detail-level params)
                service-status-version (get-service-status-version params)
                status (call-status-fn-for-service
                         service-name
                         service-info
                         level
                         (or (get-timeout params) (check-timeout level))
                         service-status-version)]
            (ringutils/json-response (status->code status)
              (assoc status :service_name service-name)))
          ;; else (no service with that name)
          (ringutils/json-response 404
             {:kind :service-not-found
              :msg  (i18n/tru "No status information found for service {0}"
                              service-name)})))))

(schema/defn ^:always-validate errors-by-type-middleware
  [t :- ringutils/ResponseType]
  (fn [handler]
    (-> handler
        (middleware/wrap-data-errors t)
        (middleware/wrap-schema-errors t)
        (middleware/wrap-uncaught-errors t))))

(defn build-handler [path status-fns]
  (comidi/routes->handler
   (comidi/wrap-routes
    (comidi/context path
      (comidi/context "/v1"
        (-> (build-json-routes "/services" status-fns)
            (comidi/wrap-routes (errors-by-type-middleware :json)))
        (-> (build-plaintext-routes "/simple" status-fns)
            (comidi/wrap-routes (errors-by-type-middleware :plain)))))
    #(i18n/locale-negotiator (ring-defaults/wrap-defaults % ring-defaults/api-defaults)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Status Service Status

(schema/defn ^:always-validate v1-status :- StatusCallbackResponse
  [last-cpu-snapshot :- (schema/atom cpu/CpuUsageSnapshot)
   level :- ServiceStatusDetailLevel]
  (let [level>= (partial compare-levels >= level)]
    {:state :running
     :status (cond->
              ;; no status info at ':critical' level
              {}
              ;; no extra status at ':info' level yet
              (level>= :info) identity
              (level>= :debug) (assoc-in [:experimental :jvm-metrics]
                                         (get-jvm-metrics @last-cpu-snapshot)))}))

(schema/defn status-latest-version :- StatusCallbackResponse
  "This function will return the status data from the latest version of the API"
  [last-cpu-snapshot :- (schema/atom cpu/CpuUsageSnapshot)
   level :- ServiceStatusDetailLevel]
  (v1-status last-cpu-snapshot level))

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
