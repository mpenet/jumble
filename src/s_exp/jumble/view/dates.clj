(ns s-exp.jumble.view.dates
  "Dates and times for display, in the community's language and time zone
  ([[*zone*]], bound per request), as `<time>` elements."
  (:require [s-exp.jumble.view.i18n :as i18n])
  (:import (java.time Instant ZoneId ZoneOffset ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(set! *warn-on-reflection* true)

(def ^:dynamic ^ZoneId *zone*
  "The zone times are shown in."
  ZoneOffset/UTC)

(def ^:private formatter
  "The formatter of `pattern` in `language`."
  (memoize (fn [pattern language]
             (DateTimeFormatter/ofPattern pattern (Locale/forLanguageTag language)))))

(defn- formatted
  "`t` as `pattern`, given translated: languages write dates their own way."
  [pattern ^ZonedDateTime t]
  (.format ^DateTimeFormatter (formatter pattern (i18n/language)) t))

(defn- at
  ^ZonedDateTime [epoch-ms]
  (.atZone (Instant/ofEpochMilli epoch-ms) *zone*))

(defn- element
  [epoch-ms text]
  [:time {:datetime (str (Instant/ofEpochMilli epoch-ms))} text])

(defn date
  "28 October 2026"
  [epoch-ms]
  (element epoch-ms (formatted (i18n/t "d MMMM yyyy") (at epoch-ms))))

(defn date-time
  "28 Oct, 14:05, with the year when not that of `now` (28 Oct 2025, 14:05)."
  ([epoch-ms]
   (date-time epoch-ms (System/currentTimeMillis)))
  ([epoch-ms now]
   (let [t (at epoch-ms)]
     (element epoch-ms
              (if (= (.getYear t) (.getYear (at now)))
                (formatted (i18n/t "d MMM, HH:mm") t)
                (formatted (i18n/t "d MMM yyyy, HH:mm") t))))))
