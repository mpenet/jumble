(ns s-exp.jumble.i18n-catalogs-test
  "The translation catalogs: in step with the code, every text translated
  with the same placeholders."
  (:require [clojure.test :refer [deftest is testing]]
            [i18n-catalogs :as catalogs]
            [s-exp.jumble.view.i18n :as i18n]))

(set! *warn-on-reflection* true)

(def ^:private messages (delay (catalogs/messages)))

(defn- placeholders
  [s]
  (set (re-seq #"\{[a-z-]+\}" s)))

(deftest template-test
  (testing "the template has the text of the code: run clojure -M:dev -m i18n-catalogs after changing it"
    (is (= (slurp "resources/i18n/jumble.pot") (catalogs/catalog-text nil @messages {})))))

(deftest translated-test
  (doseq [language (sort (disj i18n/languages "en"))
          :let [translations (catalogs/translations language)]
          {:keys [msgid msgid-plural]} @messages
          [index english] (cond-> [[0 msgid]] msgid-plural (conj [1 msgid-plural]))
          :let [translation (get-in translations [msgid index])]]
    (testing (str language ": " msgid)
      (is (seq translation) "translated")
      (when (seq translation)
        (is (= (placeholders english) (placeholders translation)) "with the same placeholders")))))
