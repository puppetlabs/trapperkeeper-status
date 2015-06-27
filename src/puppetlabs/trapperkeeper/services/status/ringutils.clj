;; TODO: Much of this code is copied from other projects. It should
;; probably be moved into a shared library or some such.
(ns puppetlabs.trapperkeeper.services.status.ringutils
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [ring.util.response :as response]
            [schema.core :as schema]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.kitchensink.core :as ks]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def ResponseType (schema/enum :json :plain))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public
(defn json-response [status body]
  (-> body
      json/encode
      response/response
      (response/status status)
      (response/content-type "application/json; charset=utf-8")))

(defn plain-response [status body]
  (-> body
      response/response
      (response/status status)
      (response/content-type "text/plain; charset=utf-8")))

(schema/defn ^:always-validate wrap-request-data-errors
  "A ring middleware that catches slingshot errors of :type
  :request-dava-invalid and returns a 400."
  [handler type :- ResponseType]
  (let [code 400
        response (fn [e]
                   (case type
                     :json (json-response code e)
                     :plain (plain-response code (:message e))))]
    (fn [request]
      (try+ (handler request)
            (catch
                #(contains? #{:request-data-invalid :service-status-version-not-found}
                            (:type %))
                e
              (response e))))))

(schema/defn ^:always-validate wrap-schema-errors
  "A ring middleware that catches schema errors and returns a 500
  response with the details"
  [handler type :- ResponseType]
  (let [code 500
        response (fn [e]
                   (let [msg (str "Something unexpected happened: "
                                  (select-keys (.getData e) [:error :value :type]))]
                     (case type
                       :json (json-response code
                               {:type :application-error
                                :message msg})
                       :plain (plain-response code msg))))]
    (fn [request]
      (try (handler request)
           (catch clojure.lang.ExceptionInfo e
             (let [message (.getMessage e)]
               (if (re-find #"does not match schema" message)
                 (response e)
                 ;; re-throw exceptions that aren't schema errors
                 (throw e))))))))

(schema/defn ^:always-validate wrap-errors
  "A ring middleware that catches all otherwise uncaught errors and
  returns a 500 response with the error message"
  [handler type :- ResponseType]
  (let [code 500
        response (fn [e]
                   (let [msg (str "Error on server: " e)]
                     (case type
                       :json (json-response code
                               {:type :application-error
                                :message msg})
                       :plain (plain-response code msg))))]
    (fn [request]
      (try (handler request)
           (catch Exception e
             (log/error e "Error on server")
             (response e))))))
