(ns puppetlabs.trapperkeeper.services.status.cpu-monitor
  (:require [clojure.java.jmx :as jmx]
            [puppetlabs.kitchensink.core :as ks]
            [clojure.tools.logging :as log]
            [schema.core :as schema])
  (:import (java.lang.management ManagementFactory)
           (java.util ArrayList)
           (javax.management AttributeNotFoundException)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def CpuUsageSnapshot
  {:snapshot {:uptime schema/Int
              :process-cpu-time schema/Int
              :process-gc-time schema/Int}
   :cpu-usage schema/Num
   :gc-cpu-usage schema/Num})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn- cpu-multiplier*
  []
  (if (contains?
       (vec (jmx/attribute-names "java.lang:type=OperatingSystem"))
       :ProcessingCapacity)
    (jmx/read "java.lang:type=OperatingSystem" :ProcessingCapacity)
    1))

(def cpu-multiplier (memoize cpu-multiplier*))

(defn- gc-bean-names*
  []
  (jmx/mbean-names "java.lang:type=GarbageCollector,*"))

(def gc-bean-names (memoize gc-bean-names*))

(defn get-process-cpu-time
  []
  (let [bean-cpu-time (jmx/read "java.lang:type=OperatingSystem" :ProcessCpuTime)]
    (* bean-cpu-time (cpu-multiplier))))

(defn get-collection-time
  []
  (try
    (apply + (map #(jmx/read % :CollectionTime) (gc-bean-names)))
    (catch AttributeNotFoundException e
      ;; Hopefully we will never hit this code path, but if we do, we should just
      ;; log a warning and bail rather than letting the exception bubble up.
      (log/warn "Found GC Bean that does not contain `:CollectionTime` attribute: "
                (gc-bean-names))
      0)))

(defn calculate-usage
  [process-time prev-process-time uptime-diff]
  (if (or (= -1 prev-process-time) (<= uptime-diff 0))
    0
    (let [process-time-diff (- process-time prev-process-time)]
      (min (* 100 (/ process-time-diff uptime-diff)) 100))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn get-cpu-values :- CpuUsageSnapshot
  [last-snapshot :- CpuUsageSnapshot]
  (let [{prev-uptime :uptime
         prev-process-cpu-time :process-cpu-time
         prev-process-gc-time :process-gc-time} (:snapshot last-snapshot)]
    (let [runtime-bean (ManagementFactory/getRuntimeMXBean)
          ;; could cache / memoize num-cpus
          num-cpus (ks/num-cpus)
          uptime (* (.getUptime runtime-bean) 1000000)
          process-cpu-time (/ (get-process-cpu-time) num-cpus)
          process-gc-time (/ (* (get-collection-time) 1000000) num-cpus)
          uptime-diff (if (= -1 prev-uptime) uptime (- uptime prev-uptime))
          cpu-usage (calculate-usage process-cpu-time prev-process-cpu-time uptime-diff)
          gc-usage (calculate-usage process-gc-time prev-process-gc-time uptime-diff)]

      (let [result {:snapshot {:uptime uptime
                               :process-cpu-time process-cpu-time
                               :process-gc-time process-gc-time}
                    :cpu-usage (float (max cpu-usage 0))
                    :gc-cpu-usage (float (max gc-usage 0))}]
        (log/trace "Latest cpu usage metrics: " (ks/pprint-to-string result))
        result))))