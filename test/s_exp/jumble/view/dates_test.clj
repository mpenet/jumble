(ns s-exp.jumble.view.dates-test
  "Times shown on pages, in the configured zone."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.onionpancakes.chassis.core :as h]
            [s-exp.jumble.view.dates :as dates]
            [s-exp.jumble.view.i18n :as i18n])
  (:import (java.time ZoneId)))

(set! *warn-on-reflection* true)

(def ^:private at (.toEpochMilli (java.time.Instant/parse "2026-10-28T14:05:00Z")))
(def ^:private same-year (.toEpochMilli (java.time.Instant/parse "2026-12-01T00:00:00Z")))
(def ^:private next-year (.toEpochMilli (java.time.Instant/parse "2027-03-01T00:00:00Z")))

(deftest time-elements-test
  (testing "machine readable, in UTC by default"
    (is (= "<time datetime=\"2026-10-28T14:05:00Z\">28 Oct, 14:05</time>" (h/html (dates/date-time at same-year))))
    (is (= "<time datetime=\"2026-10-28T14:05:00Z\">28 October 2026</time>" (h/html (dates/date at))))))

(deftest zone-test
  (testing "in the community's time zone"
    (binding [dates/*zone* (ZoneId/of "Europe/Zurich")]
      (is (= "<time datetime=\"2026-10-28T14:05:00Z\">28 Oct, 15:05</time>" (h/html (dates/date-time at same-year)))))))

(deftest other-year-test
  (testing "times from another year say which"
    (is (= "<time datetime=\"2026-10-28T14:05:00Z\">28 Oct 2026, 14:05</time>" (h/html (dates/date-time at next-year))))))

(deftest languages-test
  (testing "each language's date patterns are ones Java formats"
    (doseq [language i18n/languages]
      (binding [i18n/*catalog* (i18n/catalog language)]
        (let [oct-28 1761660300000]
          (is (re-find #"2025" (h/html (dates/date oct-28))) language)
          (is (re-find #"14[:.]05" (h/html (dates/date-time oct-28 oct-28))) language)
          (is (re-find #"2025" (h/html (dates/date-time oct-28 (+ oct-28 (* 400 24 60 60 1000))))) language))))))
