(ns puppetlabs.trapperkeeper.services.status.status-logging-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.services.status.status-logging :as status-logging]
            [slingshot.test]))

(deftest start-background-logging-test
  (let [done-promise (promise)
        callback-atom (atom :not-done)
        callback (fn []
                   (reset! callback-atom :done)
                   (deliver done-promise :done))
        task-future (status-logging/start-background-task 1 callback)]
    (testing "Callback gets called"
      @done-promise
      (is (= :done @callback-atom))
      (future-cancel task-future))))
