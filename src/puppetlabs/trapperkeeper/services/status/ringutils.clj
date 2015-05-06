;; TODO: Much of this code is copied from other projects. It should
;; probably be moved into a shared library or some such.
(ns puppetlabs.trapperkeeper.services.status.ringutils
  (:require [clojure.tools.logging :as log]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.kitchensink.core :as ks]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn wrap-request-data-errors
  "A ring middleware that catches slingshot errors of :type
  :request-dava-invalid and returns a 400."
  [handler]
  (fn [request]
    (try+ (handler request)
          (catch #(contains? #{:request-data-invalid :service-status-version-not-found}
                             (:type %)) e
            {:status 400
             :body {:error e}}))))

(defn wrap-schema-errors
  "A ring middleware that catches schema errors and returns a 500
  response with the details"
  [handler]
  (fn [request]
    (try (handler request)
         (catch clojure.lang.ExceptionInfo e
           (let [message (.getMessage e)]
             (if (re-find #"does not match schema" message)
               {:status 500
                :body {:error {:type :application-error
                               :message (str "Something unexpected happened: "
                                             (select-keys (.getData e) [:error :value :type]))}}}
               ;; re-throw exceptions that aren't schema errors
               (throw e)))))))

(defn wrap-errors
  "A ring middleware that catches all otherwise uncaught errors and
  returns a 500 response with the error message"
  [handler]
  (fn [request]
    (try (handler request)
         (catch Exception e
           (log/error e "Error on server")
           {:status 500
            :body {:error {:type :application-error
                           :message (str "Error on server: " e)}}}))))
