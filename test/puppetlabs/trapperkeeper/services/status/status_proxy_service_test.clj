(ns puppetlabs.trapperkeeper.services.status.status-proxy-service-test
  (:require [clojure.test :refer :all]
    [cheshire.core :as json]
    [schema.test :as schema-test]
    [puppetlabs.http.client.sync :as http-client]
    [puppetlabs.trapperkeeper.core :refer [defservice service]]
    [puppetlabs.trapperkeeper.testutils.bootstrap :refer :all]
    [puppetlabs.trapperkeeper.services.status.status-service :refer [status-service]]
    [puppetlabs.trapperkeeper.services.status.status-proxy-service :refer [status-proxy-service]]
    [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
    [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting-service]
    [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9-service]))

(use-fixtures :once schema-test/validate-schemas)

(def common-ssl-config
  {:ssl-cert    "./dev-resources/config/ssl/certs/localhost.pem"
   :ssl-key     "./dev-resources/config/ssl/private_keys/localhost.pem"
   :ssl-ca-cert "./dev-resources/config/ssl/certs/ca.pem"})

(def ssl-status-service-config
  {:webserver (merge common-ssl-config
                {:ssl-host "0.0.0.0"
                 :ssl-port 9001})
   :web-router-service {:puppetlabs.trapperkeeper.services.status.status-service/status-service "/ssl-status"}})

(def status-proxy-service-config
  {:webserver          {:port 8181
                        :host "0.0.0.0"}
   :status-proxy       {:proxy-target-url "https://0.0.0.0:9001/ssl-status"
                        :ssl-opts common-ssl-config}
   :web-router-service {:puppetlabs.trapperkeeper.services.status.status-proxy-service/status-proxy-service "/status-proxy"}})


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

(deftest proxy-ssl-status-endpoint-test
  (testing "status-proxy-service can connect to https status-service service correctly"
    ; Start status service
    (with-app-with-config
      status-app
      [jetty9-service/jetty9-service
       webrouting-service/webrouting-service
       status-service
       foo-service
       bar-service]
      ssl-status-service-config
      ; Start the proxy service
      (with-app-with-config
        proxy-app
        [jetty9-service/jetty9-service
         webrouting-service/webrouting-service
         status-proxy-service]
        status-proxy-service-config
        ; Make HTTP request to the proxy, which will forward it as an HTTPS request
        (testing "proxying plain url"
          (let [resp (http-client/get "http://localhost:8181/status-proxy/v1/services")
                body (json/parse-string (slurp (:body resp)))]
            (is (= 200 (:status resp)))
            (is (= {"bar" {"service_version"        "0.1.0"
                           "service_status_version" 1
                           "is_running"             "false"
                           "detail_level"           "info"
                           "status"                 "bar status 1 :info"}
                    "foo" {"service_version"        "1.1.0"
                           "service_status_version" 2
                           "is_running"             "true"
                           "detail_level"           "info"
                           "status"                 "foo status 2 :info"}}
                  body))))
        (testing "proxying url with query param"
          (let [resp (http-client/get "http://localhost:8181/status-proxy/v1/services?level=debug")
                body (json/parse-string (slurp (:body resp)))]
            (is (= 200 (:status resp)))
            (is (= {"bar" {"service_version"        "0.1.0"
                           "service_status_version" 1
                           "is_running"             "false"
                           "detail_level"           "debug"
                           "status"                 "bar status 1 :debug"}
                    "foo" {"service_version"        "1.1.0"
                           "service_status_version" 2
                           "is_running"             "true"
                           "detail_level"           "debug"
                           "status"                 "foo status 2 :debug"}}
                  body))))
        (testing "proxying specific service"
          (let [resp (http-client/get "http://localhost:8181/status-proxy/v1/services/foo")
                body (json/parse-string (slurp (:body resp)))]
            (is (= 200 (:status resp)))
            (is (= {"service_version"        "1.1.0"
                    "service_status_version" 2
                    "is_running"             "true"
                    "detail_level"           "info"
                    "status"                 "foo status 2 :info"
                    "service_name"           "foo"}
                  body))))))))

(defn count-ring-handler
  "Increments counter"
  [counter req]
  {:status 200
   :body   (str (swap! counter inc))})

(deftest proxy-only-proxies-what-it-should-test
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
