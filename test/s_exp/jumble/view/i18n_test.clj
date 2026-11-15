(ns s-exp.jumble.view.i18n-test
  "Translating text: catalogs, plurals, markup, unknown languages."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [s-exp.ex :as ex]
            [s-exp.jumble.error :as-alias error]
            [s-exp.jumble.view.i18n :as i18n]))

(set! *warn-on-reflection* true)

(def catalog (i18n/read-catalog "fr" (io/resource "i18n/xx.po")))

(deftest english-test
  (testing "without a catalog, the English text itself"
    (is (= "Listings" (i18n/t "Listings")))
    (is (= "Posted by Ann" (i18n/t "Posted by {name}" {:name "Ann"})))
    (is (= "1 listing" (i18n/tn "{n} listing" "{n} listings" 1)))
    (is (= "0 listings" (i18n/tn "{n} listing" "{n} listings" 0)))
    (is (= "en" (i18n/language)))))

(deftest catalog-test
  (binding [i18n/*catalog* catalog]
    (testing "translated, placeholders filled"
      (is (= "Annonces" (i18n/t "Listings")))
      (is (= "Publiée par Ann" (i18n/t "Posted by {name}" {:name "Ann"})))
      (is (= "fr" (i18n/language))))
    (testing "escapes and strings over several lines"
      (is (= "Dis \"salut\"\nsur deux lignes" (i18n/t "Say \"hi\"\non two lines"))))
    (testing "plurals follow the language: 0 is singular in French"
      (is (= "0 annonce" (i18n/tn "{n} listing" "{n} listings" 0)))
      (is (= "1 annonce" (i18n/tn "{n} listing" "{n} listings" 1)))
      (is (= "2 annonces" (i18n/tn "{n} listing" "{n} listings" 2))))
    (testing "fuzzy, empty or missing translations fall back to English"
      (is (= "Unsure" (i18n/t "Unsure")))
      (is (= "Untranslated" (i18n/t "Untranslated")))
      (is (= "Not in the catalog" (i18n/t "Not in the catalog"))))))

(deftest markup-test
  (testing "a value that is markup makes the result markup, in the translation's order"
    (binding [i18n/*catalog* catalog]
      (is (= (list "Publiée par " [:strong "Ann"])
             (i18n/t "Posted by {name}" {:name [:strong "Ann"]}))))))

(deftest plural-markup-test
  (testing "the count can be given as markup, still choosing the form"
    (is (= (list [:strong 2] " things") (i18n/tn "{n} thing" "{n} things" 2 {:n [:strong 2]})))
    (is (= (list [:strong 1] " thing") (i18n/tn "{n} thing" "{n} things" 1 {:n [:strong 1]})))))

(deftest unknown-language-test
  (testing "a language without a catalog or a plural rule is refused"
    (is (= ::error/no-catalog (ex/ex-type (try (i18n/catalog "zz") nil
                                               (catch clojure.lang.ExceptionInfo e e)))))
    (is (= ::error/no-plural-rule (ex/ex-type (try (i18n/read-catalog "xx" (io/resource "i18n/xx.po")) nil
                                                   (catch clojure.lang.ExceptionInfo e e)))))))

(deftest languages-test
  (testing "the languages spoken, each named in itself, in the order offered"
    (is (= [["en" "English"] ["fr" "Français"] ["de" "Deutsch"] ["it" "Italiano"] ["es" "Español"] ["pt" "Português"]
            ["sv" "Svenska"] ["da" "Dansk"] ["nb" "Norsk bokmål"] ["fi" "Suomi"] ["nl" "Nederlands"]]
           i18n/language-names))
    (is (= (set (map first i18n/language-names)) i18n/languages))
    (testing "each with a catalog but English"
      (doseq [l (disj i18n/languages "en")]
        (is (some? (i18n/catalog l)) l)))))
