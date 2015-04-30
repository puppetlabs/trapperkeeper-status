(ns puppetlabs.trapperkeeper.services.status.status-service-test
  (:require [clojure.test :refer :all]
            [cheshire.core :as json]
            [schema.test :as schema-test]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer :all]
            [puppetlabs.trapperkeeper.services.status.status-service :refer :all]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting-service]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9-service]))

(use-fixtures :once schema-test/validate-schemas)

(def status-service-config
  {:webserver {:port 8180
               :host "0.0.0.0"}
   :web-router-service
     {:puppetlabs.trapperkeeper.services.status.status-service/status-service "/status"}})

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
    (register-status "foo" "1.1.0" 1 (fn [level] (str "foo status 1 " level)))
    (register-status "foo" "1.1.0" 2 (fn [level] (str "foo status 2 " level)))
    context))

(defservice bar-service
  [[:StatusService register-status]]
  (init [this context]
    (register-status "bar" "0.1.0" 1 (fn [level] (str "bar status 1 " level)))
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
        (is (= {"bar" {"service-version" "0.1.0"
                       "service-status-version" 1
                       "status" "bar status 1 :info"}
                "foo" {"service-version" "1.1.0"
                       "service-status-version" 2
                       "status" "foo status 2 :info"}}
               body))))
    (testing "uses status level from query param"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services?level=debug")
            body (json/parse-string (slurp (:body resp)))]
        (is (= 200 (:status resp)))
        (is (= {"bar" {"service-version" "0.1.0"
                       "service-status-version" 1
                       "status" "bar status 1 :debug"}
                "foo" {"service-version" "1.1.0"
                       "service-status-version" 2
                       "status" "foo status 2 :debug"}}
               body))))))

(deftest single-service-status-endpoint-test
  (with-status-service
    app
    [foo-service]
    (testing "returns service information for service that has registered a callback"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services/foo")]
        (is (= 200 (:status resp)))
        (is (= {"service-version" "1.1.0"
                "service-status-version" 2
                "status" "foo status 2 :info"
                "service-name" "foo"}
               (json/parse-string (slurp (:body resp)))))))
    (testing "uses status level query param"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services/foo?level=critical")]
        (is (= 200 (:status resp)))
        (is (= {"service-version" "1.1.0"
                "service-status-version" 2
                "status" "foo status 2 :critical"
                "service-name" "foo"}
               (json/parse-string (slurp (:body resp)))))))
    (testing "uses service-status-version query param"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services/foo?service-status-version=1")]
        (is (= 200 (:status resp)))
        (is (= {"service-version" "1.1.0"
                "service-status-version" 1
                "status" "foo status 1 :info"
                "service-name" "foo"}
               (json/parse-string (slurp (:body resp)))))))
    (testing "returns a 404 for service not registered with the status service"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services/notfound")]
        (is (= 404 (:status resp)))
        (is (= {"error" {"type" "service-not-found"
                         "message" "No status information found for service notfound"}}
               (json/parse-string (slurp (:body resp)))))))))

(deftest error-handling-test
  (with-status-service app
    [foo-service]
    (testing "returns a 400 when an invalid level is queried for"
      (let [resp (http-client/get "http://localhost:8180/status/v1/services?level=bar")]
        (is (= 400 (:status resp)))
        (is (= {"error" {"type" "request-data-invalid"
                         "message" "Invalid level: :bar"}}
               (json/parse-string (slurp (:body resp)))))))
    (testing "returns a 400 when a non-integer status-version is queried for"
      (let [resp (http-client/get (str "http://localhost:8180/status/v1/"
                                       "services/foo?service-status-version=abc"))]
        (is (= 400 (:status resp)))
        (is (= {"error" {"type"    "request-data-invalid"
                         "message" (str "Invalid service-status-version. "
                                        "Should be an integer but was abc")}}
               (json/parse-string (slurp (:body resp)))))))
    (testing "returns a 400 when a non-existent status-version is queried for"
      (let [resp (http-client/get (str "http://localhost:8180/status/v1/"
                                        "services/foo?service-status-version=3"))]
        (is (= 400 (:status resp)))
        (is (= {"error" {"type"    "service-status-version-not-found"
                         "message" (str "No status function with version 3 "
                                        "found for service foo")}}
               (json/parse-string (slurp (:body resp)))))))))
