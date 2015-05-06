(ns puppetlabs.trapperkeeper.services.status.status-core-test
  (:require [clojure.test :refer :all]
            [schema.core :as schema]
            [schema.test :as schema-test]
            [puppetlabs.trapperkeeper.services.status.status-core :refer :all]
            [trptcolin.versioneer.core :as versioneer]))

(use-fixtures :once schema-test/validate-schemas)

(deftest get-service-version-test
  (testing "get-service-version returns a version string that satisfies the schema"
    ;; This test coverage isn't very thorough, but anything beyond this would
    ;; really just be testing the underlying libraries that we use to
    ;; implement it.
    (is (nil? (schema/check
                SemVerVersion
                (get-service-version "puppetlabs" "trapperkeeper-status"))))
    (is (thrown? IllegalStateException
                 (get-service-version "fake-group" "artifact-that-does-not-exist")))
    (with-redefs [versioneer/get-version (constantly "bad-version-string")]
      (is (thrown? IllegalStateException
                   (get-service-version "puppetlabs" "trapperkeeper-status"))))))

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
