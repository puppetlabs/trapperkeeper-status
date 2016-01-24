(ns puppetlabs.trapperkeeper.services.status.status-service-test
  (:require [clojure.test :refer :all]
            [cheshire.core :as json]
            [schema.test :as schema-test]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.trapperkeeper.services :refer [defservice service]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.trapperkeeper.services.status.status-service :refer [status-service get-status]]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting-service]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9-service]
            [puppetlabs.kitchensink.core :as ks]))

(use-fixtures :once schema-test/validate-schemas)

(def status-service-config
  {:webserver {:port 8180
               :host "0.0.0.0"}
   :web-router-service {:puppetlabs.trapperkeeper.services.status.status-service/status-service "/status"}})

(defn parse-response
  ([resp]
   (parse-response resp false))
  ([resp keywordize?]
   (json/parse-string (slurp (:body resp)) keywordize?)))

(defn response->status
  [resp]
  (:status (parse-response resp true)))

(defmacro with-status-service
  "Macro to start the status service and its dependencies (jetty9 and
  webrouting service), along with any other services desired."
  [app services & body]
  `(with-app-with-config
     ~app
     (concat [jetty9-service/jetty9-service
              webrouting-service/webrouting-service
              status-service] ~services)
     status-service-config
     (do ~@body)))

(defservice foo-service
  [[:StatusService register-status]]
  (init [this context]
    (register-status "foo" "1.1.0" 1 (fn [level] {:status (str "foo status 1 " level)
                                                  :state :running}))
    (register-status "foo" "1.1.0" 2 (fn [level] {:status (str "foo status 2 " level)
                                                  :state :running}))
    context))

(defservice bar-service
  [[:StatusService register-status]]
  (init [this context]
    (register-status "bar" "0.1.0" 1 (fn [level] {:status (str "bar status 1 " level)
                                                  :state :running}))
    context))

(defservice baz-service
  [[:StatusService register-status]]
  (init [this context]
    (register-status "baz" "0.2.0" 1 (fn [level] "baz"))
    context))

(defservice fail-service
  [[:StatusService register-status]]
  (init [this context]
    (register-status "fail" "4.2.0" 1 (fn [level] {:status "wheee", :state :error}))
    context))

(defservice starting-service
  [[:StatusService register-status]]
  (init [this context]
        (register-status "starting" "6.6.6" 1 (fn [level] {:status "foo"
                                                           :state :starting}))))

(defservice stopping-service
  [[:StatusService register-status]]
  (init [this context]
        (register-status "stopping" "6.6.6" 1 (fn [level] {:status "bar"
                                                           :state :stopping}))))

(defservice slow-service
  [[:StatusService register-status]]
  (init [this context]
        (register-status "slow" "0.1.0" 1 (fn [level] (Thread/sleep 2000)))))

(defservice broken-service
  [[:StatusService register-status]]
  (init [this context]
        (register-status "broken" "0.1.0" 1 (fn [level] (throw (Exception. "don't"))))))

(deftest get-status-test
  (with-status-service app [foo-service
                            jetty9-service/jetty9-service
                            webrouting-service/webrouting-service
                            status-service]
    (let [svc (get-service app :StatusService)]
      (is (= (get-status svc "foo" :critical nil)
             {:state :running :status "foo status 2 :critical"}) "can get the status from the latest status fn")
      (is (= (get-status svc "foo" :critical 1)
             {:state :running :status "foo status 1 :critical"}) "can get the status from a specific status version")
      (is (= (get-status svc "foo" :info nil)
             {:state :running :status "foo status 2 :info"}) "can select the status fn level"))))

(deftest rollup-status-endpoint-test
  (with-status-service
    app
    [foo-service
     bar-service]
    (testing "returns latest status for all services"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services")
            body (parse-response resp)]
        (is (= 200 (:status resp)))
        (is (= {"bar" {"service_version" "0.1.0"
                       "service_status_version" 1
                       "state" "running"
                       "detail_level" "info"
                       "status" "bar status 1 :info"}
                "foo" {"service_version" "1.1.0"
                       "service_status_version" 2
                       "state" "running"
                       "detail_level" "info"
                       "status" "foo status 2 :info"}}
               (dissoc body "status-service")))))
    (testing "uses status level from query param"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services?level=debug")
            body (parse-response resp)]
        (is (= 200 (:status resp)))
        (is (= {"bar" {"service_version" "0.1.0"
                       "service_status_version" 1
                       "state" "running"
                       "detail_level" "debug"
                       "status" "bar status 1 :debug"}
                "foo" {"service_version" "1.1.0"
                       "service_status_version" 2
                       "state" "running"
                       "detail_level" "debug"
                       "status" "foo status 2 :debug"}}
               (dissoc body "status-service")))))))

(deftest alternate-mount-point-test
  (testing "can mount status endpoint at alternate location"
    (with-app-with-config
      app
      [jetty9-service/jetty9-service
       webrouting-service/webrouting-service
       status-service]
      (merge status-service-config
        {:web-router-service {:puppetlabs.trapperkeeper.services.status.status-service/status-service "/alternate-status"}})
      (let [resp (http-client/get "http://localhost:8180/alternate-status/v1/services")]
        (is (= 200 (:status resp)))))))

(deftest single-service-status-endpoint-test
  (with-status-service
    app
    [foo-service
     baz-service]
    (testing "returns service information for service that has registered a callback"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services/foo")]
        (is (= 200 (:status resp)))
        (is (= {"service_version" "1.1.0"
                "service_status_version" 2
                "state" "running"
                "detail_level" "info"
                "status" "foo status 2 :info"
                "service_name" "foo"}
              (parse-response resp)))))
    (testing "uses status level query param"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services/foo?level=critical")]
        (is (= 200 (:status resp)))
        (is (= {"service_version" "1.1.0"
                "service_status_version" 2
                "state" "running"
                "detail_level" "critical"
                "status" "foo status 2 :critical"
                "service_name" "foo"}
              (parse-response resp)))))
    (testing "uses service_status_version query param"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services/foo?service_status_version=1")]
        (is (= 200 (:status resp)))
        (is (= {"service_version" "1.1.0"
                "service_status_version" 1
                "state" "running"
                "detail_level" "info"
                "status" "foo status 1 :info"
                "service_name" "foo"}
              (parse-response resp)))))
    (testing "returns unknown for state if not provided in callback fn"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services/baz")]
        (is (= 503 (:status resp)))
        (is (= {"service_version" "0.2.0"
                "service_status_version" 1
                "state" "unknown"
                "detail_level" "info"
                "status" "Status check malformed: (not (map? \"baz\"))"
                "service_name" "baz"}
              (parse-response resp)))))
    (testing "returns a 404 for service not registered with the status service"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services/notfound")]
        (is (= 404 (:status resp)))
        (is (= {"type" "service-not-found"
                "message" "No status information found for service notfound"}
              (parse-response resp)))))))

(deftest status-code-test
    (with-status-service app
      [bar-service fail-service]
      (testing "returns 503 response code when a service is not running"
        (let [{:keys [status]} (http-client/get "http://localhost:8180/status/v1/services/fail")]
          (is (= 503 status)))
        (let [{:keys [status]} (http-client/get "http://localhost:8180/status/v1/services")]
          (is (= 503 status))))

      (testing "returns a 200 response code for the service that is running"
        (let [{:keys [status]} (http-client/get "http://localhost:8180/status/v1/services/bar")]
          (is (= 200 status)))))

  (testing "returns 200 response code when all services are running"
    (with-status-service app
      [bar-service foo-service]
      (let [{:keys [status]} (http-client/get "http://localhost:8180/status/v1/services/bar")]
        (is (= 200 status)))
      (let [{:keys [status]} (http-client/get "http://localhost:8180/status/v1/services")]
        (is (= 200 status))))))

(deftest status-check-error-handling-test
  (with-test-logging
    (with-status-service
      app
      [slow-service
       broken-service
       baz-service]
      (testing "handles case when a status check times out"
        (with-redefs [puppetlabs.trapperkeeper.services.status.status-core/check-timeout (constantly 1)]
          (let [resp (http-client/get "http://localhost:8180/status/v1/services/slow?level=critical")
                body (parse-response resp)]
            (is (= 503 (:status resp)))
            (is (= "unknown"
                  (get body "state")))
            (is (re-find #"timed out" (get body "status"))))))

      (testing "handles case when a status check throws an exception"
        (let [resp (http-client/get "http://localhost:8180/status/v1/services/broken?level=critical")
              body (parse-response resp)]
          (is (= 503 (:status resp)))
          (is (= "unknown"
                (get body "state")))
          (is (re-find #"exception.*don't" (get body "status")))))
      (testing "handles case when a status check returns a non-conforming result"
        (let [resp (http-client/get "http://localhost:8180/status/v1/services/baz?level=critical")
              body (parse-response resp)]
          (is (= 503 (:status resp)))
          (is (= "unknown"
                (get body "state")))
          (is (re-find #"malformed" (get body "status"))))))))

(deftest error-handling-test
  (with-status-service app
    [foo-service]
    (testing "returns a 400 when an invalid level is queried for"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services?level=bar")]
        (is (= 400 (:status resp)))
        (is (= {"type" "request-data-invalid"
                "message" "Invalid level: :bar"}
              (parse-response resp)))))
    (testing "returns a 400 when a non-integer status-version is queried for"
      (let [resp (http-client/get (str "http://localhost:8180/status/v1/"
                                    "services/foo?service_status_version=abc"))]
        (is (= 400 (:status resp)))
        (is (= {"type"    "request-data-invalid"
                "message" (str "Invalid service_status_version. "
                            "Should be an integer but was abc")}
              (parse-response resp)))))
    (testing "returns a 400 when a non-existent status-version is queried for"
      (let [resp (http-client/get (str "http://localhost:8180/status/v1/"
                                    "services/foo?service_status_version=3"))]
        (is (= 400 (:status resp)))
        (is (= {"type"    "service-status-version-not-found"
                "message" (str "No status function with version 3 "
                            "found for service foo")}
              (parse-response resp)))))))


(deftest simple-routes-params-ignoring-test
  (with-status-service app
    [foo-service]
    (testing "ignores bad level"
      (let [resp (http-client/get "http://localhost:8180/status/v1/simple?level=bar")]
        (is (= 200 (:status resp)))
        (is (= "running" (slurp (:body resp))))))
    (testing "ignores alphabetic service_status_version"
      (let [resp (http-client/get (str "http://localhost:8180/status/v1/"
                                       "simple/foo?service_status_version=abc"))]
        (is (= 200 (:status resp)))
        (is (= "running" (slurp (:body resp))))))
    (testing "ignores non-existent service_status_version"
      (let [resp (http-client/get (str "http://localhost:8180/status/v1/"
                                       "simple/foo?service_status_version=3"))]
        (is (= 200 (:status resp)))
        (is (= "running" (slurp (:body resp))))))))

(deftest simple-routes-test
  (testing "when calling the simple routes"
    (testing "for all services"
      (testing "and all services are :running"
        (with-status-service app
          [foo-service bar-service]
          (let [resp (http-client/get "http://localhost:8180/status/v1/simple")]
            (is (= 200 (:status resp)))
            (is (= "running" (slurp (:body resp)))))))

      (testing "and a service is :error"
        (with-status-service app
          [foo-service baz-service fail-service]
          (let [resp (http-client/get "http://localhost:8180/status/v1/simple")]
            (is (= 503 (:status resp)))
            (is (= "error" (slurp (:body resp)))))))

      (testing "and one service is :starting while another is :stopping"
        (with-status-service app
          [foo-service bar-service starting-service stopping-service]
          (let [resp (http-client/get "http://localhost:8180/status/v1/simple")]
            (is (= 503 (:status resp)))
            (is (= "stopping" (slurp (:body resp)))))))

      (testing "and a service is :unknown"
        (with-status-service app
          [foo-service baz-service]
          (let [resp (http-client/get "http://localhost:8180/status/v1/simple")]
            (is (= 503 (:status resp)))
            (is (= "unknown" (slurp (:body resp))))))))

    (testing "for a single service"
      (with-status-service app
        [foo-service baz-service fail-service starting-service]
        (testing "and it is :running"
          (let [resp (http-client/get "http://localhost:8180/status/v1/simple/foo")]
            (is (= 200 (:status resp)))
            (is (= "running" (slurp (:body resp))))))
        (testing "and it is :unknown"
          (let [resp (http-client/get "http://localhost:8180/status/v1/simple/baz")]
            (is (= 503 (:status resp)))
            (is (= "unknown" (slurp (:body resp))))))
        (testing "and it is :error"
          (let [resp (http-client/get "http://localhost:8180/status/v1/simple/fail")]
            (is (= 503 (:status resp)))
            (is (= "error" (slurp (:body resp))))))
        (testing "and it is :starting"
          (let [resp (http-client/get "http://localhost:8180/status/v1/simple/starting")]
            (is (= 503 (:status resp)))
            (is (= "starting" (slurp (:body resp))))))
        (testing "and it does not exist"
          (let [resp (http-client/get "http://localhost:8180/status/v1/simple/kafka")]
            (is (= 404 (:status resp)))
            (is (= "not found: kafka" (slurp (:body resp))))))))))

(deftest compare-levels-test
  (testing "use of compare-levels to implement a status function"
    (let [my-status (fn [level]
                      (let [level>= (partial status-core/compare-levels >= level)]
                        {:state :running
                         :status (cond-> {:this-is-critical "foo"}
                                   (level>= :info) (assoc :bar "bar"
                                                          :baz "baz")
                                   (level>= :debug) (assoc :x "x"
                                                           :y "y"
                                                           :z "y"))}))
          my-service (service [[:StatusService register-status]]
                       (init [this context]
                         (register-status "my-service" "1.0.0" 1 my-status)))]
      (with-status-service app
        [my-service]
        (testing "critical"
          (let [resp (http-client/get "http://localhost:8180/status/v1/services/my-service?level=critical")]
            (is (= 200 (:status resp)))
            (is (= {:this-is-critical "foo"}
                  (response->status resp)))))
        (testing "info"
          (let [resp (http-client/get "http://localhost:8180/status/v1/services/my-service?level=info")]
            (is (= 200 (:status resp)))
            (is (= {:this-is-critical "foo"
                    :bar "bar"
                    :baz "baz"}
                  (response->status resp)))))
        (testing "debug"
          (let [resp (http-client/get "http://localhost:8180/status/v1/services/my-service?level=debug")]
            (is (= 200 (:status resp)))
            (is (= {:this-is-critical "foo"
                    :bar "bar"
                    :baz "baz"
                    :x "x"
                    :y "y"
                    :z "y"}
                  (response->status resp)))))))))

(deftest content-type-test
  (testing "responses have the 'application/json' content type set"
    (with-status-service app
      [foo-service]
      (let [{:keys [headers]} (http-client/get "http://localhost:8180/status/v1/services/foo")]
        (is (re-find #"^application/json" (get headers "content-type")))))))

(deftest status-status-test
  (testing "trapperkeeper-status registers its own status callback"
    (with-status-service
     app []
     (let [resp (http-client/get "http://localhost:8180/status/v1/services")]
       (is (= 200 (:status resp)))
       (is (= #{:status-service} (ks/keyset (parse-response resp true)))))
     (let [resp (http-client/get "http://localhost:8180/status/v1/services/status-service?level=debug")]
       (is (= 200 (:status resp)))
       (let [body (parse-response resp true)]
         (is (= {:detail_level "debug"
                 :service_name "status-service"
                 :service_status_version 1
                 :service_version status-core/status-service-version
                 :state "running"}
                (dissoc body :status)))
         (is (map? (get-in body [:status :experimental :jvm-metrics]))))))))
