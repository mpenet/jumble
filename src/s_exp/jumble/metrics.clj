(ns s-exp.jumble.metrics
  "What the process counts and times while it runs, for GET /metrics:
  counters, gauges and histograms, kept in memory from its start and
  written in Prometheus' text format. A metric is defined once, by name;
  its series by their labels."
  (:require [clojure.string :as str])
  (:import (java.lang.management GarbageCollectorMXBean ManagementFactory MemoryMXBean MemoryUsage)
           (com.sun.management OperatingSystemMXBean)))

(set! *warn-on-reflection* true)

(defonce ^:private registry
  ;; name -> {:type :help :buckets :series {labels value}}
  (atom (sorted-map)))

(defn- define
  [name type help buckets]
  (swap! registry #(if (contains? % name) % (assoc % name {:type type :help help :buckets buckets :series {}})))
  name)

(defn counter
  "A count that only grows, named `name` (ending in _total); the `series`
  known in advance, label maps, start at 0, so that the first count of
  each shows as one."
  ([name help] (define name :counter help nil))
  ([name help series]
   (define name :counter help nil)
   (swap! registry update-in [name :series] #(merge (zipmap series (repeat 0)) %))
   name))

(defn gauge
  "A value that goes up and down."
  [name help]
  (define name :gauge help nil))

(def ^:private seconds-buckets
  "Upper bounds, in seconds, of the durations a histogram counts: from a
  request answered at once to a slow upload."
  [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5 5 10])

(defn histogram
  "Durations in seconds, counted by the bucket they fall in."
  [name help]
  (define name :histogram help seconds-buckets))

(defn inc!
  "Adds `n` (1 by default) to counter `metric`'s series of `labels`."
  ([metric labels] (inc! metric labels 1))
  ([metric labels n]
   (swap! registry update-in [metric :series labels] (fnil + 0) n)
   nil))

(defn set-gauge!
  [metric labels value]
  (swap! registry assoc-in [metric :series labels] value)
  nil)

(defn observe!
  "Counts `seconds` in histogram `metric`'s series of `labels`."
  [metric labels seconds]
  (swap! registry
         update-in
         [metric]
         (fn [{:keys [buckets] :as m}]
           (update-in m
                      [:series labels]
                      (fn [{:keys [counts sum n] :or {sum 0.0 n 0}}]
                        {:counts (mapv (fn [bound c] (cond-> (or c 0) (<= seconds bound) inc))
                                       buckets
                                       (or counts (repeat nil)))
                         :sum (+ sum seconds)
                         :n (inc n)}))))
  nil)

(defn- escape
  [v]
  (-> (str v) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"") (str/replace "\n" "\\n")))

(defn- label-text
  "`{a=\"1\",b=\"2\"}` for `labels` in order of their names, then `extra`
  pairs as given; empty for none."
  [labels & extra]
  (let [pairs (concat (sort-by key labels) (partition 2 extra))]
    (if (empty? pairs)
      ""
      (str "{" (str/join "," (map (fn [[k v]] (str (name k) "=\"" (escape v) "\"")) pairs)) "}"))))

(defn- series-lines
  [name {:keys [type buckets series]}]
  (if (= :histogram type)
    (mapcat (fn [[labels {:keys [counts sum n]}]]
              (concat (map (fn [bound c] (str name "_bucket" (label-text labels :le bound) " " c))
                           buckets
                           counts)
                      [(str name "_bucket" (label-text labels :le "+Inf") " " n)
                       (str name "_sum" (label-text labels) " " sum)
                       (str name "_count" (label-text labels) " " n)]))
            (sort-by (comp str key) series))
    (map (fn [[labels v]] (str name (label-text labels) " " v))
         (sort-by (comp str key) series))))

(defn text
  "Every metric defined, with its series, in Prometheus' text format."
  []
  (str/join (for [[name {:keys [type help] :as m}] @registry]
              (str/join "\n"
                        (concat [(str "# HELP " name " " help)
                                 (str "# TYPE " name " " (clojure.core/name type))]
                                (series-lines name m)
                                [""])))))

(defn- metric-text
  "One metric, described, with its series: `[labels value]` pairs."
  [name type help series]
  (str/join (concat [(str "# HELP " name " " help "\n") (str "# TYPE " name " " type "\n")]
                    (map (fn [[labels v]] (str name (label-text labels) " " v "\n")) series))))

(defn jvm-text
  "The JVM's memory, collections, threads and process time, read now, in
  Prometheus' text format, under the names its clients use."
  []
  (let [^MemoryMXBean memory (ManagementFactory/getMemoryMXBean)
        ^MemoryUsage heap (.getHeapMemoryUsage memory)
        ^MemoryUsage nonheap (.getNonHeapMemoryUsage memory)
        gcs (ManagementFactory/getGarbageCollectorMXBeans)
        ^OperatingSystemMXBean os (ManagementFactory/getOperatingSystemMXBean)]
    (str (metric-text "jvm_memory_used_bytes"
                      "gauge"
                      "Memory in use, by area."
                      [[{:area "heap"} (.getUsed heap)] [{:area "nonheap"} (.getUsed nonheap)]])
         (metric-text "jvm_memory_committed_bytes"
                      "gauge"
                      "Memory the JVM holds from the system, by area."
                      [[{:area "heap"} (.getCommitted heap)] [{:area "nonheap"} (.getCommitted nonheap)]])
         (metric-text "jvm_memory_max_bytes"
                      "gauge"
                      "The most memory the area may use, -1 when unbounded."
                      [[{:area "heap"} (.getMax heap)] [{:area "nonheap"} (.getMax nonheap)]])
         (metric-text "jvm_gc_collections_total"
                      "counter"
                      "Garbage collections, by collector."
                      (for [^GarbageCollectorMXBean gc gcs] [{:gc (.getName gc)} (.getCollectionCount gc)]))
         (metric-text "jvm_gc_collection_seconds_total"
                      "counter"
                      "Time spent collecting garbage, by collector."
                      (for [^GarbageCollectorMXBean gc gcs] [{:gc (.getName gc)} (/ (.getCollectionTime gc) 1000.0)]))
         (metric-text "jvm_threads_current"
                      "gauge"
                      "Live threads."
                      [[{} (.getThreadCount (ManagementFactory/getThreadMXBean))]])
         (metric-text "process_cpu_seconds_total"
                      "counter"
                      "Processor time the process used."
                      [[{} (/ (.getProcessCpuTime os) 1e9)]])
         (metric-text "process_start_time_seconds"
                      "gauge"
                      "When the process started, in seconds since the epoch."
                      [[{} (/ (.getStartTime (ManagementFactory/getRuntimeMXBean)) 1000.0)]]))))
