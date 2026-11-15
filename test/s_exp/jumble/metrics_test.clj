(ns s-exp.jumble.metrics-test
  "What the process counts and times, in Prometheus' text format."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.metrics :as metrics]))

(set! *warn-on-reflection* true)

(defn- lines
  []
  (set (str/split-lines (metrics/text))))

(deftest counter-test
  (let [c (metrics/counter "test_things_total" "Things counted.")]
    (metrics/inc! c {:kind "a"})
    (metrics/inc! c {:kind "a"})
    (metrics/inc! c {:kind "b"} 5)
    (testing "described once, each series by its labels"
      (is (contains? (lines) "# HELP test_things_total Things counted."))
      (is (contains? (lines) "# TYPE test_things_total counter"))
      (is (contains? (lines) "test_things_total{kind=\"a\"} 2"))
      (is (contains? (lines) "test_things_total{kind=\"b\"} 5")))
    (testing "defined again, the same counter, its counts kept"
      (metrics/inc! (metrics/counter "test_things_total" "Things counted.") {:kind "a"})
      (is (contains? (lines) "test_things_total{kind=\"a\"} 3")))))

(deftest known-series-test
  (testing "series known in advance start at 0, so a first count is not missed"
    (metrics/counter "test_outcomes_total" "Outcomes." [{:outcome "won"} {:outcome "lost"}])
    (is (contains? (lines) "test_outcomes_total{outcome=\"won\"} 0"))
    (is (contains? (lines) "test_outcomes_total{outcome=\"lost\"} 0"))))

(deftest labels-test
  (let [c (metrics/counter "test_labelled_total" "Labels.")]
    (metrics/inc! c {:z "1" :a "say \"hi\"\\\n"})
    (metrics/inc! (metrics/counter "test_bare_total" "No labels.") {})
    (testing "sorted, their values escaped"
      (is (contains? (lines) "test_labelled_total{a=\"say \\\"hi\\\"\\\\\\n\",z=\"1\"} 1")))
    (testing "none at all"
      (is (contains? (lines) "test_bare_total 1")))))

(deftest gauge-test
  (let [g (metrics/gauge "test_level" "A level.")]
    (metrics/set-gauge! g {:step "a"} 7)
    (metrics/set-gauge! g {:step "a"} 3)
    (is (contains? (lines) "# TYPE test_level gauge"))
    (is (contains? (lines) "test_level{step=\"a\"} 3"))))

(deftest histogram-test
  (let [h (metrics/histogram "test_seconds" "Durations.")]
    (doseq [v [0.003 0.2 0.2 7.0 30.0]]
      (metrics/observe! h {:route "/x"} v))
    (testing "cumulative buckets, their sum and count"
      (is (contains? (lines) "# TYPE test_seconds histogram"))
      (is (contains? (lines) "test_seconds_bucket{route=\"/x\",le=\"0.005\"} 1"))
      (is (contains? (lines) "test_seconds_bucket{route=\"/x\",le=\"0.1\"} 1"))
      (is (contains? (lines) "test_seconds_bucket{route=\"/x\",le=\"0.25\"} 3"))
      (is (contains? (lines) "test_seconds_bucket{route=\"/x\",le=\"10\"} 4"))
      (is (contains? (lines) "test_seconds_bucket{route=\"/x\",le=\"+Inf\"} 5"))
      (is (contains? (lines) "test_seconds_count{route=\"/x\"} 5"))
      (is (some #(str/starts-with? % "test_seconds_sum{route=\"/x\"} 37.40") (lines))))))
