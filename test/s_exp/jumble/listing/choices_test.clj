(ns s-exp.jumble.listing.choices-test
  "Categories and pickup places: adding, renaming, ordering and retiring."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.listing.choices :as choices]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(defn- labels
  [which]
  (mapv :label (get (choices/all *db*) which)))

(deftest all-test
  (testing "the database's categories and places, in order"
    (is (= {:categories [{:id "home" :label "Furniture & home" :retired-at nil}
                         {:id "other" :label "Other" :retired-at nil}]
            :places [{:id "hall" :label "Main hall" :retired-at nil}]}
           (choices/all *db*)))))

(deftest slug-test
  (is (= "bikes-sport" (choices/slug "Bikes & sport")))
  (is (= "velos-electriques" (choices/slug "  Vélos électriques ")))
  (is (= "" (choices/slug "家具"))))

(deftest add-test
  (testing "added last, with an id from its label"
    (is (= {:id "bikes-sport"} (choices/add! *db* :category "Bikes & sport")))
    (is (= ["Furniture & home" "Other" "Bikes & sport"] (labels :categories))))
  (testing "ids stay unique; labels without letters get one from the list"
    (is (= {:id "bikes-sport-2"} (choices/add! *db* :category "Bikes, sport")))
    (is (= {:id "place"} (choices/add! *db* :place "家具"))))
  (testing "labels are trimmed, required, short and unique whatever their case"
    (is (= {:id "library"} (choices/add! *db* :place "  Library  ")))
    (is (= "Library" (last (labels :places))))
    (is (= {:errors {:label "Give it a name."}} (choices/add! *db* :place " ")))
    (is (= {:errors {:label "Keep the name to 60 characters or fewer."}}
           (choices/add! *db* :place (apply str (repeat 61 "a")))))
    (is (= {:errors {:label "There is already one named “Library”."}}
           (choices/add! *db* :place "library")))))

(deftest rename-test
  (is (= {:ok true} (choices/rename! *db* :category "home" "Home & garden")))
  (is (= ["Home & garden" "Other"] (labels :categories)))
  (testing "its own label in another case is fine; another's is not"
    (is (= {:ok true} (choices/rename! *db* :category "home" "HOME & garden")))
    (is (= {:errors {:label "There is already one named “Other”."}}
           (choices/rename! *db* :category "home" "other"))))
  (testing "unknown ids"
    (is (nil? (choices/rename! *db* :category "nope" "Nope")))))

(deftest move-test
  (choices/add! *db* :category "Books")
  (choices/move! *db* :category "books" :up)
  (is (= ["Furniture & home" "Books" "Other"] (labels :categories)))
  (choices/move! *db* :category "home" :down)
  (is (= ["Books" "Furniture & home" "Other"] (labels :categories)))
  (testing "the ends stay put"
    (choices/move! *db* :category "books" :up)
    (choices/move! *db* :category "other" :down)
    (is (= ["Books" "Furniture & home" "Other"] (labels :categories))))
  (testing "retired entries are skipped over"
    (choices/retire! *db* :category "home" 5)
    (choices/move! *db* :category "other" :up)
    (is (= ["Other" "Books"] (mapv :label (choices/choosable (:categories (choices/all *db*))))))))

(deftest retire-test
  (testing "retired entries stay, marked, and are no longer choosable"
    (is (= {:ok true} (choices/retire! *db* :category "home" 5)))
    (is (= 5 (:retired-at (first (:categories (choices/all *db*))))))
    (is (= ["other"] (mapv :id (choices/choosable (:categories (choices/all *db*)))))))
  (testing "the last category stays: a listing needs one"
    (is (= {:errors {:label "Keep at least one category."}} (choices/retire! *db* :category "other" 5))))
  (testing "places can all go: pickup is then in a city"
    (is (= {:ok true} (choices/retire! *db* :place "hall" 5))))
  (testing "restored, back where it was"
    (is (= {:ok true} (choices/restore! *db* :category "home")))
    (is (= ["Furniture & home" "Other"] (labels :categories)))
    (is (= ["home" "other"] (mapv :id (choices/choosable (:categories (choices/all *db*))))))))

(deftest counts-test
  (fixtures/listing! (fixtures/user! "ann") "Lamp")
  (is (= {"home" 1} (choices/listing-counts *db* :category)))
  (is (= {"hall" 1} (choices/listing-counts *db* :place))))

(deftest localize-defaults-test
  (binding [i18n/*catalog* (i18n/catalog "fr")]
    (choices/rename! *db* :category "other" "Divers")
    (choices/localize-defaults! *db*)
    (testing "default categories still named in English take the instance's language"
      (is (= "Meubles et maison" (:label (first (:categories (choices/all *db*)))))))
    (testing "those moderators renamed are left alone"
      (is (= "Divers" (:label (second (:categories (choices/all *db*)))))))
    (testing "running again changes nothing"
      (choices/localize-defaults! *db*)
      (is (= ["Meubles et maison" "Divers"] (labels :categories))))))
