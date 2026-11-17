(ns s-exp.jumble.account.join-test
  "The community's join link: anyone holding it may join, until moderators
  renew it, turn it off, or it expires."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [s-exp.jumble.account.join :as join]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def ^:private day (* 24 60 60 1000))

(deftest link-test
  (let [dave (fixtures/user! "dave")]
    (testing "none until a moderator makes one"
      (is (nil? (join/current *db* 0)))
      (is (not (join/valid? *db* "anything" 0))))
    (testing "made, it lets in whoever holds its token"
      (let [token (join/create! *db* {:moderator-id dave :expires-in nil :now 1000})]
        (is (join/valid? *db* token 2000))
        (is (= {:token token :created-at 1000 :expires-at nil :created-by "dave"}
               (select-keys (join/current *db* 2000) [:token :created-at :expires-at :created-by])))
        (is (not (join/valid? *db* (str token "x") 2000)))
        (is (not (join/valid? *db* nil 2000)))
        (testing "renewed, the earlier one no longer does"
          (let [renewed (join/create! *db* {:moderator-id dave :expires-in nil :now 3000})]
            (is (not= token renewed))
            (is (not (join/valid? *db* token 4000)))
            (is (join/valid? *db* renewed 4000))
            (testing "turned off, none does"
              (join/turn-off! *db*)
              (is (not (join/valid? *db* renewed 5000)))
              (is (nil? (join/current *db* 5000))))))))
    (testing "an expiry, after which it lets nobody in"
      (let [token (join/create! *db* {:moderator-id dave :expires-in (* 7 day) :now 0})]
        (is (join/valid? *db* token (dec (* 7 day))))
        (is (= (* 7 day) (:expires-at (join/current *db* 0))))
        (is (not (join/valid? *db* token (* 7 day))))
        (is (nil? (join/current *db* (* 7 day))))))))
