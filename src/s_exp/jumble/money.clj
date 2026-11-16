(ns s-exp.jumble.money
  "Prices are integers in the minor unit of the deployment's currency; the
  currency's ISO 4217 definition decides how many decimals that is."
  (:refer-clojure :exclude [format])
  (:require [clojure.string :as str]
            [s-exp.jumble.view.i18n :as i18n])
  (:import (java.math BigDecimal)
           (java.text DecimalFormat DecimalFormatSymbols)
           (java.util Currency Locale)))

(set! *warn-on-reflection* true)

(def ^:private max-major
  "Largest accepted price, in major units."
  1000000)

(defn- fraction-digits
  ^long [^String currency]
  (.getDefaultFractionDigits (Currency/getInstance currency)))

(defn parse
  "Minor units for the amount typed in `s`, or nil when it is not a positive
  price within bounds with at most the currency's decimals. Accepts `.` or
  `,` as decimal mark, and spaces (no-break ones too) or apostrophes between
  thousands."
  [currency s]
  (when (string? s)
    (when-let [[_ whole fraction] (re-matches #"(\d{1,7})(?:[.,](\d+))?"
                                              (str/replace (str/trim s) #"['\s\u00a0\u202f]" ""))]
      (let [digits (fraction-digits currency)]
        (when (<= (count fraction) digits)
          (let [minor (.longValueExact (.movePointRight (BigDecimal. (str whole "." (or fraction "0"))) digits))]
            (when (<= 1 minor (.longValueExact (.movePointRight (BigDecimal/valueOf (long max-major)) digits)))
              minor)))))))

(defn- amount
  ^BigDecimal [currency minor]
  (BigDecimal/valueOf (long minor) (fraction-digits currency)))

(defn- whole?
  [^BigDecimal a]
  (zero? (.signum (.remainder a BigDecimal/ONE))))

(defn format
  "`minor` for display, in the language in use: currency code, grouped
  digits, decimals only when the amount has a fractional part."
  [currency minor]
  (let [a (amount currency minor)
        pattern (if (whole? a)
                  "#,##0"
                  (str "#,##0." (apply str (repeat (fraction-digits currency) "0"))))
        symbols (DecimalFormatSymbols. (Locale/forLanguageTag (i18n/language)))]
    (i18n/t "{currency} {amount}"
            {:currency currency
             :amount (.format (DecimalFormat. pattern symbols) a)})))

(defn input-value
  "`minor` as typed into a price field, which [[parse]] reads back."
  [currency minor]
  (let [a (amount currency minor)]
    (if (whole? a)
      (.toPlainString (.setScale a 0))
      (.toPlainString a))))
