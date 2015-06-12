(ns puppetlabs.trapperkeeper.services.status.status-service-test
  (:require [clojure.test :refer :all]
            [cheshire.core :as json]
            [schema.test :as schema-test]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.trapperkeeper.core :refer [defservice service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer :all]
            [puppetlabs.trapperkeeper.services.status.status-service :refer [status-service status-proxy-service]]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting-service]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9-service]))

(use-fixtures :once schema-test/validate-schemas)

(def common-ssl-config
  {:ssl-cert    "./dev-resources/config/ssl/certs/localhost.pem"
   :ssl-key     "./dev-resources/config/ssl/private_keys/localhost.pem"
   :ssl-ca-cert "./dev-resources/config/ssl/certs/ca.pem"})

(def status-service-config
  {:webserver {:port 8180
               :host "0.0.0.0"}
   :web-router-service {:puppetlabs.trapperkeeper.services.status.status-service/status-service "/status"}})

(def ssl-status-service-config
  {:webserver (merge common-ssl-config
                {:ssl-host "0.0.0.0"
                 :ssl-port 9001})
   :web-router-service {:puppetlabs.trapperkeeper.services.status.status-service/status-service "/ssl-status"}})

(def status-proxy-service-config
  {:webserver          {:port 8181
                        :host "0.0.0.0"}
   :status-proxy       {:target-host    "0.0.0.0"
                        :target-port    9001
                        :target-url     "/ssl-status"
                        :target-options {:ssl-config common-ssl-config
                                         :scheme :https}}
   :web-router-service {:puppetlabs.trapperkeeper.services.status.status-service/status-proxy-service "/status-proxy"}})

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
                                                  :is-running :false}))
    context))

(defservice baz-service
  [[:StatusService register-status]]
  (init [this context]
    (register-status "baz" "0.2.0" 1 (fn [level] "baz"))
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
                       "is_running" "false"
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
                       "is_running" "false"
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
        (is (= 200 (:status resp)))
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
                        {:is-running true
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

(deftest proxy-ssl-status-endpoint-test
  (testing "status-proxy-service can connect to https status-service service correctly"
    ; Start status service
    (with-app-with-config
      status-app
      [jetty9-service/jetty9-service
       webrouting-service/webrouting-service
       status-service
       foo-service]
      ssl-status-service-config
      ; Start the proxy service
      (with-app-with-config
        proxy-app
        [jetty9-service/jetty9-service
         webrouting-service/webrouting-service
         status-proxy-service]
        status-proxy-service-config
        ; Make HTTP request to the proxy, which will forward it as an HTTPS request
        (let [resp (http-client/get "http://localhost:8181/status-proxy/v1/services")
              body (json/parse-string (slurp (:body resp)))]
          (is (= 200 (:status resp)))
          (is (= {"foo" {"service_version"        "1.1.0"
                         "service_status_version" 2
                         "is_running"             "true"
                         "detail_level"           "info"
                         "status"                 "foo status 2 :info"}}
                body)))))))

(defn count-ring-handler
  "Increments counter"
  [counter req]
  {:status 200
   :body   (str (swap! counter inc))})

(deftest proxy-ssl-status-endpoint-test
  (testing "status-proxy-service doesn't proxy things it shouldn't"
    ; non-status-endpoint-counter is used to make sure that no requests to the proxy
    ; endpoint end up hitting non status-service endpoints
    (let [non-status-endpoint-counter (atom 0)
          status-ring-handler (partial count-ring-handler non-status-endpoint-counter)
          status-count-service (service
                                 [[:WebserverService add-ring-handler]]
                                 (init [this context]
                                   (add-ring-handler status-ring-handler "/stats")
                                   (add-ring-handler status-ring-handler "/nope")
                                   (add-ring-handler status-ring-handler "/ssl-statuses")
                                   (add-ring-handler status-ring-handler "/statuses")
                                   context))
          ; Should not succeed in hitting the endpoints running on the status service server
          bad-proxy-requests ["http://localhost:8181/status-proxy/stats"
                              "http://localhost:8181/status-proxy/nope"
                              "http://localhost:8181/status-proxy/statuses"]
          ; Should succeed, used to make sure the counter works right
          good-non-status-endpoint-requests ["https://localhost:9001/stats"
                                             "https://localhost:9001/nope"
                                             "https://localhost:9001/ssl-statuses"
                                             "https://localhost:9001/statuses"]]
      (with-app-with-config
        ; Start status service
        status-app
        [jetty9-service/jetty9-service
         webrouting-service/webrouting-service
         status-service
         status-count-service]
        ssl-status-service-config
        ; Start the proxy service
        (with-app-with-config
          proxy-app
          [jetty9-service/jetty9-service
           webrouting-service/webrouting-service
           status-proxy-service]
          status-proxy-service-config
          (testing "non-proxied endpoints on status-service server don't see any traffic"
            (doall (map http-client/get bad-proxy-requests))
            (is (= 0 (deref non-status-endpoint-counter))))
          (testing "that the counter is correctly catching requests"
            (doall (map #(http-client/get % common-ssl-config) good-non-status-endpoint-requests))
            (is (= (count good-non-status-endpoint-requests)
                  (deref non-status-endpoint-counter)))))))))
