(ns puppetlabs.trapperkeeper.services.status.status-service-test
  (:require [clojure.test :refer :all]
            [cheshire.core :as json]
            [schema.test :as schema-test]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.trapperkeeper.core :refer [defservice service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer :all]
            [puppetlabs.trapperkeeper.services.status.status-service :refer [status-service]]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting-service]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9-service]))

(use-fixtures :once schema-test/validate-schemas)

(def status-service-config
  {:webserver {:port 8180
               :host "0.0.0.0"}
   :web-router-service {:puppetlabs.trapperkeeper.services.status.status-service/status-service "/status"}})

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
                                                  :is-running :true}))
    (register-status "foo" "1.1.0" 2 (fn [level] {:status (str "foo status 2 " level)
                                                  :is-running :true}))
    context))

(defservice bar-service
  [[:StatusService register-status]]
  (init [this context]
    (register-status "bar" "0.1.0" 1 (fn [level] {:status (str "bar status 1 " level)
                                                  :is-running :true}))
    context))

(defservice baz-service
  [[:StatusService register-status]]
  (init [this context]
    (register-status "baz" "0.2.0" 1 (fn [level] "baz"))
    context))

(defservice fail-service
  [[:StatusService register-status]]
  (init [this context]
    (register-status "fail" "4.2.0" 1 (fn [level] {:status "wheee", :is-running :false}))
    context))

(deftest rollup-status-endpoint-test
  (with-status-service
    app
    [foo-service
     bar-service]
    (testing "returns latest status for all services"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services")
            body (json/parse-string (slurp (:body resp)))]
        (is (= 200 (:status resp)))
        (is (= {"bar" {"service_version" "0.1.0"
                       "service_status_version" 1
                       "is_running" "true"
                       "detail_level" "info"
                       "status" "bar status 1 :info"}
                "foo" {"service_version" "1.1.0"
                       "service_status_version" 2
                       "is_running" "true"
                       "detail_level" "info"
                       "status" "foo status 2 :info"}}
              body))))
    (testing "uses status level from query param"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services?level=debug")
            body (json/parse-string (slurp (:body resp)))]
        (is (= 200 (:status resp)))
        (is (= {"bar" {"service_version" "0.1.0"
                       "service_status_version" 1
                       "is_running" "true"
                       "detail_level" "debug"
                       "status" "bar status 1 :debug"}
                "foo" {"service_version" "1.1.0"
                       "service_status_version" 2
                       "is_running" "true"
                       "detail_level" "debug"
                       "status" "foo status 2 :debug"}}
              body))))))

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
                "is_running" "true"
                "detail_level" "info"
                "status" "foo status 2 :info"
                "service_name" "foo"}
              (json/parse-string (slurp (:body resp)))))))
    (testing "uses status level query param"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services/foo?level=critical")]
        (is (= 200 (:status resp)))
        (is (= {"service_version" "1.1.0"
                "service_status_version" 2
                "is_running" "true"
                "detail_level" "critical"
                "status" "foo status 2 :critical"
                "service_name" "foo"}
              (json/parse-string (slurp (:body resp)))))))
    (testing "uses service_status_version query param"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services/foo?service_status_version=1")]
        (is (= 200 (:status resp)))
        (is (= {"service_version" "1.1.0"
                "service_status_version" 1
                "is_running" "true"
                "detail_level" "info"
                "status" "foo status 1 :info"
                "service_name" "foo"}
              (json/parse-string (slurp (:body resp)))))))
    (testing "returns unknown for is_running if not provided in callback fn"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services/baz")]
        (is (= 503 (:status resp)))
        (is (= {"service_version" "0.2.0"
                "service_status_version" 1
                "is_running" "unknown"
                "detail_level" "info"
                "status" nil
                "service_name" "baz"}
              (json/parse-string (slurp (:body resp)))))))
    (testing "returns a 404 for service not registered with the status service"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services/notfound")]
        (is (= 404 (:status resp)))
        (is (= {"type" "service-not-found"
                "message" "No status information found for service notfound"}
              (json/parse-string (slurp (:body resp)))))))))

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

(deftest error-handling-test
  (with-status-service app
    [foo-service]
    (testing "returns a 400 when an invalid level is queried for"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services?level=bar")]
        (is (= 400 (:status resp)))
        (is (= {"type" "request-data-invalid"
                "message" "Invalid level: :bar"}
              (json/parse-string (slurp (:body resp)))))))
    (testing "returns a 400 when a non-integer status-version is queried for"
      (let [resp (http-client/get (str "http://localhost:8180/status/v1/"
                                    "services/foo?service_status_version=abc"))]
        (is (= 400 (:status resp)))
        (is (= {"type"    "request-data-invalid"
                "message" (str "Invalid service_status_version. "
                            "Should be an integer but was abc")}
              (json/parse-string (slurp (:body resp)))))))
    (testing "returns a 400 when a non-existent status-version is queried for"
      (let [resp (http-client/get (str "http://localhost:8180/status/v1/"
                                    "services/foo?service_status_version=3"))]
        (is (= 400 (:status resp)))
        (is (= {"type"    "service-status-version-not-found"
                "message" (str "No status function with version 3 "
                            "found for service foo")}
              (json/parse-string (slurp (:body resp)))))))))

(defn response->status
  [resp]
  (:status (json/parse-string (slurp (:body resp)) true)))

(deftest compare-levels-test
  (testing "use of compare-levels to implement a status function"
    (let [my-status (fn [level]
                      (let [level>= (partial status-core/compare-levels >= level)]
                        {:is-running :true
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
