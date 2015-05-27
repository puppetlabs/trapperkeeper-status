(ns puppetlabs.trapperkeeper.services.status.check
  "Shared status check functions."
  (:require [clojure.java.jdbc :as sql])
  (:import [java.io File IOException]))

(defn db-up?
  [db-spec]
  (try (let [select-42 "SELECT (a - b) AS answer FROM (VALUES ((7 * 7), 7)) AS x(a, b)"
             [{:keys [answer]}] (sql/query db-spec [select-42])]
         (= answer 42))
    (catch Exception _
      false)))

(defn disk-writable?
  []
  (if-let [temp-file (try (File/createTempFile "tk-disk-check" ".txt")
                       (catch IOException _ nil))]
    (let [payload (apply str (map char (repeatedly 4095 #(rand-nth (range 97 123)))))]
      (try
        (spit temp-file payload)
        (= (slurp temp-file) payload)
        (catch Exception _
          false)
        (finally
          (try (.delete temp-file)
            (catch IOException _
              false)))))
    false))
