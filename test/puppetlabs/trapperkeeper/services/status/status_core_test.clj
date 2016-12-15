(ns puppetlabs.trapperkeeper.services.status.status-core-test
  (:require [clojure.test :refer :all]
            [schema.core :as schema]
            [schema.test :as schema-test]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.trapperkeeper.services.status.status-core :refer :all]
            [slingshot.test]
            [puppetlabs.kitchensink.core :as ks]))

(use-fixtures :once schema-test/validate-schemas)

(deftest get-service-version-test
  (testing "get-service-version returns a version string that satisfies the schema"
    ;; This test coverage isn't very thorough, but anything beyond this would
    ;; really just be testing the underlying libraries that we use to
    ;; implement it.
    (is (thrown-with-msg? IllegalStateException
          #"Unable to find version number for"
          (get-artifact-version "fake-group" "artifact-that-does-not-exist")))))

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

(deftest get-status-fn-test
  (testing "getting the status function with an unspecified status version"
    (let [status-fns (atom {})]
      (update-status-context status-fns "foo" "1.1.0" 1 (fn [] "foo v1"))
      (update-status-context status-fns "foo" "1.1.0" 2 (fn [] "foo v2"))
      (update-status-context status-fns "bar" "8.0.1" 1 (fn [] "bar v1"))

      (let [status-fn (get-status-fn status-fns "foo" nil)]
        (is (= "foo v2" (status-fn)))
        (is (thrown+? [:kind :service-info-not-found :msg "No service info found for service baz"]
                      (get-status-fn status-fns "baz" nil)))
        (is (thrown+? [:kind :service-status-version-not-found
                       :msg "No status function with version 2 found for service bar"]
                      (get-status-fn status-fns "bar" 2)))))))

(deftest error-handling-test
  (testing "when there is an error checking status"

    (let [status-fns (atom {})]
      (testing "and it is a bad callback result schema"
        (update-status-context status-fns "foo" "1.1.0" 1 (fn [_] {:totally :nonconforming}))
        (let [result (call-status-fn-for-service "foo" (get @status-fns "foo") :debug 1)]
          (testing "status is set to explain schema error"
            (is (re-find #"missing-required-key" (pr-str result))))
          (testing "state is set properly"
            (is (= :unknown (:state result))))))

      (testing "and it is from a timeout"
        ; Add a status function that will block indefinitely to ensure we will always
        ; timeout
        (update-status-context status-fns "quux" "1.1.0" 1 (fn [_] (deref (promise)) {:state :running
                                                                                      :status "aw yis"}))
        (with-test-logging
          (let [result (call-status-fn-for-service "quux" (get @status-fns "quux") :debug 0)]
            (is (logged? #"Status callback timed out" :error))
            (is (logged? #"CancellationException"))
            (testing "state is set properly"
              (is (= :unknown (:state result))))
            (testing "status is set to explain timeout"
              (is (= "Status check timed out after 0 seconds" (:status result)))))))

      (testing "and it is from the status reporting function"
        (update-status-context status-fns "bar" "1.1.0" 1 (fn [_] (throw (Exception. "don't"))))
        (with-test-logging
          (let [result (call-status-fn-for-service "bar" (get @status-fns "bar") :debug 1)]
            (is (logged? #"Status check threw an exception" :error))
            (testing "status contains exception"
              (is (re-find #"don't" (pr-str result))))
            (testing "state is set properly"
              (= :unknown (:state result)))))))))

(deftest v1-status-test
  (let [last-cpu-snapshot (atom {:snapshot {:uptime -1
                                            :process-cpu-time -1
                                            :process-gc-time -1}
                                 :cpu-usage -1
                                 :gc-cpu-usage -1})]
    (testing "no data at critical level"
      (is (= {:state :running
              :status {}} (v1-status last-cpu-snapshot :critical))))
    (testing "no data at info level"
      (is (= {:state :running
              :status {}} (v1-status last-cpu-snapshot :info))))
    (testing "jvm metrics at debug level"
      (let [status (v1-status last-cpu-snapshot :debug)]
        (is (= {:state :running} (dissoc status :status)))
        (is (= #{:experimental} (ks/keyset (:status status))))
        (is (= #{:jvm-metrics} (ks/keyset (get-in status [:status :experimental]))))
        (let [jvm-metrics (get-in status [:status :experimental :jvm-metrics])]
          (is (= #{:heap-memory :non-heap-memory
                   :file-descriptors :gc-stats
                   :up-time-ms :start-time-ms
                   :cpu-usage :gc-cpu-usage} (ks/keyset jvm-metrics)))
          (is (= #{:committed :init :max :used} (ks/keyset (:heap-memory jvm-metrics))))
          (is (= #{:committed :init :max :used} (ks/keyset (:non-heap-memory jvm-metrics))))
          (is (every? #(< 0 %) (vals (:heap-memory jvm-metrics))))
          (is (every? #(or (< 0 %) (= -1 %)) (vals (:non-heap-memory jvm-metrics))))
          (is (= #{:max :used} (ks/keyset (:file-descriptors jvm-metrics))))
          (is (every? #(< 0 %) (vals (:file-descriptors jvm-metrics))))
          (is (every? #(= #{:count :total-time-ms} (ks/keyset %))
                      (vals (:gc-stats jvm-metrics))))
          (is (every? #(<= 0 %)                             ;; Possible that no major collections occurred.
                      (mapcat #(vals %)
                              (vals (:gc-stats jvm-metrics)))))
          (is (< 0 (:up-time-ms jvm-metrics)))
          (is (< 0 (:start-time-ms jvm-metrics))))))))
