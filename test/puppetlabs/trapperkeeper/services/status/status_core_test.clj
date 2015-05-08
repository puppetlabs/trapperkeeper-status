(ns puppetlabs.trapperkeeper.services.status.status-core-test
  (:require [clojure.test :refer :all]
            [schema.core :as schema]
            [schema.test :as schema-test]
            [puppetlabs.trapperkeeper.services.status.status-core :refer :all]))

(use-fixtures :once schema-test/validate-schemas)

(deftest update-status-context-test
  (let [status-fns (atom {})]
    (testing "registering service status callback functions"
      (update-status-context status-fns "foo" "1.1.0" 1 (fn [] "foo v1"))
      (update-status-context status-fns "foo" "1.1.0" 2 (fn [] "foo v2"))
      (update-status-context status-fns "bar" "1.1.0" 1 (fn [] "bar v1"))

      (is (nil? (schema/check ServicesInfo @status-fns)))
      (is (= 2 (count (get @status-fns "foo"))))
      (is (= 1 (count (get @status-fns "bar")))))

    (testing (str "registering a service status callback function with a "
                  "version that already exists causes an error")
      (is (thrown-with-msg? IllegalStateException
                            #"Service function already exists.*"
                            (update-status-context status-fns "foo"
                                                   "1.1.0" 2
                                                   (fn [] "foo repeat")))))

    (testing (str "registering a service status callback function with a "
                  "different service version causes an error")
      (is (thrown-with-msg? IllegalStateException
                            #"Cannot register multiple callbacks.*different service version"
                            (update-status-context status-fns "foo"
                                                   "1.2.0" 3
                                                   (fn [] "foo repeat")))))))
