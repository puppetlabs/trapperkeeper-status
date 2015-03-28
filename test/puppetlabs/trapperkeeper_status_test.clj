(ns puppetlabs.trapperkeeper-status-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper-status :refer :all]))

(deftest a-test
  (testing "I am a test"
    (is (= "Hello, World!" (foo "")))))
