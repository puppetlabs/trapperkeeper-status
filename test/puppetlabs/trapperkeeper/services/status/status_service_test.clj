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

(defservice foo-service
  [[:StatusService register-status]]
  (init [this context]
    (register-status "foo" "1.1.0" 1 (fn [] "foo status 1"))
    (register-status "foo" "1.1.0" 2 (fn [] "foo status 2"))
    context))

(defservice bar-service
  [[:StatusService register-status]]
  (init [this context]
    (register-status "bar" "0.1.0" 1 (fn [] "bar status 1"))
    context))

(deftest status-endpoint-test
  (with-app-with-config
    app
    [jetty9-service/jetty9-service
     webrouting-service/webrouting-service
     status-service
     foo-service
     bar-service]
    {:webserver {:port 8180
                 :host "0.0.0.0"}
     :web-router-service {:puppetlabs.trapperkeeper.services.status.status-service/status-service "/status"}}
    (let [req (http-client/get "http://localhost:8180/status/v1/services")
          body (json/parse-string (slurp (:body req)))]
      (is (= 200 (:status req)))
      (is (= {"service-version" "0.1.0"
              "service-status-version" 1
              "status" "bar status 1"}
            (get-in body ["services" "bar"])))
      (is (= {"service-version" "1.1.0"
              "service-status-version" 2
              "status" "foo status 2"}
            (get-in body ["services" "foo"]))))))
