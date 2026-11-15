(ns s-exp.jumble.web.params
  "Reading values out of request text: path ids and form fields."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn id
  "A database id from text, or nil when it is not one, or not text (a file
  sent where text was expected, say)."
  [s]
  (when (string? s)
    (some->> s (re-matches #"\d{1,18}") parse-long)))

(defn one
  "Form field `k` of `params` as a single string: the first value when the
  field was sent more than once, rather than a list nothing expects."
  [params k]
  (let [v (get params k)]
    (if (sequential? v) (first v) v)))

(defn trimmed
  "Form field `s` without surrounding whitespace, with line breaks as `\\n`
  (one character, as browsers count them in `maxlength`) and without control
  characters other than tabs and line breaks; nil when absent."
  [s]
  (when (string? s)
    (-> s
        (str/replace #"\r\n?" "\n")
        (str/replace #"[\p{Cc}&&[^\t\n]]" "")
        str/trim)))
