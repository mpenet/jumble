(ns s-exp.jumble.money-test
  "Prices: typed amounts parsed into the currency's minor unit, and shown."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.money :as money]))

(deftest parse-test
  (testing "amounts in major units become integers in the minor unit"
    (is (= 2500 (money/parse "CHF" "25")))
    (is (= 1250 (money/parse "CHF" "12.50")))
    (is (= 1250 (money/parse "CHF" "12,5")))
    (is (= 5 (money/parse "CHF" "0.05")))
    (is (= 120000 (money/parse "CHF" " 1'200 ")))
    (is (= 120000 (money/parse "CHF" "1 200"))))
  (testing "the currency decides how many decimals are allowed"
    (is (= 1200 (money/parse "JPY" "1200")))
    (is (nil? (money/parse "JPY" "12.5")))
    (is (= 1250 (money/parse "BHD" "1.250")))
    (is (nil? (money/parse "CHF" "1.005"))))
  (testing "anything else is not a price"
    (doseq [s [nil "" "  " "abc" "-5" "0" "0.00" "1e3" "1.2.3" "12." ".5x" "CHF 12"]]
      (is (nil? (money/parse "CHF" s)) (pr-str s))))
  (testing "bounded, so prices stay sensible and far from overflow"
    (is (= 100000000 (money/parse "CHF" "1000000")))
    (is (nil? (money/parse "CHF" "1000000.01")))
    (is (nil? (money/parse "CHF" "99999999999999999999")))))

(deftest format-test
  (is (= "CHF 25" (money/format "CHF" 2500)))
  (is (= "CHF 12.50" (money/format "CHF" 1250)))
  (is (= "CHF 0.05" (money/format "CHF" 5)))
  (is (= "CHF 1,200" (money/format "CHF" 120000)))
  (is (= "JPY 1,200" (money/format "JPY" 1200)))
  (is (= "BHD 1.250" (money/format "BHD" 1250))))

(deftest localized-format-test
  (testing "separators and the currency's place follow the language"
    (binding [i18n/*catalog* (i18n/catalog "fr")]
      (is (= "1\u202f250,50\u00a0EUR" (money/format "EUR" 125050))))
    (binding [i18n/*catalog* (i18n/catalog "de")]
      (is (= "1.250,50\u00a0EUR" (money/format "EUR" 125050))))))

(deftest input-value-test
  (testing "a stored price as it goes back into the form, parseable again"
    (is (= "25" (money/input-value "CHF" 2500)))
    (is (= "12.50" (money/input-value "CHF" 1250)))
    (is (= 1250 (money/parse "CHF" (money/input-value "CHF" 1250))))))

(deftest pasted-thousands-test
  (testing "no-break and narrow no-break spaces, as French formatting pastes them, separate thousands too"
    (is (= 125000 (money/parse "CHF" "1 250")))
    (is (= 125000 (money/parse "CHF" "1 250")))))
