(ns s-exp.jumble.demo-data-test
  "The demo's members and listings (dev/demo/listings.edn): each listing is
  one the app accepts, by one of the members, with photos it can take."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.fixtures :refer [call-with-db-path]]
            [s-exp.jumble.listing.choices :as choices]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.store.migrate :as migrate]))

(set! *warn-on-reflection* true)

(defn- new-community-choices
  "The categories and places of a new Jumble, as the demo is seeded into."
  []
  (let [choices (promise)]
    (call-with-db-path
     (fn [path]
       (with-open [ds (db/datasource path)]
         (migrate/migrate! ds (migrate/migrations))
         (deliver choices (choices/all ds)))))
    @choices))

(deftest listings-test
  (let [{:keys [members listings]} (edn/read-string (slurp "dev/demo/listings.edn"))
        settings (merge {:currency "CHF"} (new-community-choices))]
    (testing "members have distinct handles"
      (is (apply distinct? (map :handle members))))
    (doseq [{:keys [owner kind title price category city description photos]} listings]
      (testing title
        (is (< -1 owner (count members)) "owned by a member")
        (is (nil? (:errors (listing/validate settings
                                             {:kind kind :title title :description description
                                              :price price :category category
                                              :pickup "city" :city city}))))
        (is (<= (count photos) listing/max-photos))
        (is (every? #(and (:url %) (:source %)) photos) "each photo with its source")
        (is (every? #{"cc0" "pdm"} (map :license photos)) "each photo free to use")))
    (testing "titles are distinct per member"
      (is (apply distinct? (map (juxt :owner :title) listings))))))
