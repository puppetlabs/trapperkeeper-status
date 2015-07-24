(ns puppetlabs.trapperkeeper.services.status.status-core-test
  (:require [clojure.test :refer :all]
            [schema.core :as schema]
            [schema.test :as schema-test]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
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
                (get-artifact-version "puppetlabs" "trapperkeeper-status"))))
    (is (thrown-with-msg? IllegalStateException
          #"Unable to find version number for"
          (get-artifact-version "fake-group" "artifact-that-does-not-exist")))
    (with-redefs [versioneer/get-version (constantly "bad-version-string")]
      (is (thrown-with-msg? IllegalStateException
            #"does not comply with semver"
            (get-artifact-version "puppetlabs" "trapperkeeper-status"))))))

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

(deftest error-handling-test
  (testing "when there is an error checking status"

    (let [status-fns (atom {})]
      (testing "and it is a bad callback result schema"
        (update-status-context status-fns "foo" "1.1.0" 1 (fn [_] {:totally :nonconforming}))
        (let [result (call-status-fn-for-service "foo" (get @status-fns "foo") :debug)]
          (testing "status is set to explain schema error"
            (is (re-find #"missing-required-key" (pr-str result))))
          (testing "state is set properly"
            (is (= :unknown (:state result))))))

      (testing "and it is from a timeout"
        (update-status-context status-fns "quux" "1.1.0" 1 (fn [_] (Thread/sleep 2000) {:state :running
                                                                                        :status "aw yis"}))
        (with-redefs [puppetlabs.trapperkeeper.services.status.status-core/check-timeout (constantly 1)]
          (with-test-logging
            (let [result (call-status-fn-for-service "quux" (get @status-fns "quux") :debug)]
              (testing "state is set properly"
                (is (= :unknown (:state result))))
              (testing "status is set to explain timeout"
                (is (= "Status check timed out after 1 seconds" (:status result))))))))

      (testing "and it is from the status reporting function"
        (update-status-context status-fns "bar" "1.1.0" 1 (fn [_] (throw (Exception. "don't"))))
        (with-test-logging
          (let [result (call-status-fn-for-service "bar" (get @status-fns "bar") :debug)]
            (is (logged? #"Status check threw an exception" :error))
            (testing "status contains exception"
              (is (re-find #"don't" (pr-str result))))
            (testing "state is set properly"
              (= :unknown (:state result)))))))))
