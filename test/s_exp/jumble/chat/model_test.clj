(ns s-exp.jumble.chat.model-test
  "New posts queued to be announced to the community's chat channel, a few
  minutes after they are posted."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [s-exp.jumble.chat.model :as chat]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.store.blob :as blob]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(deftest queue-test
  (let [ann (fixtures/user! "ann")
        lamp (listing/create! *db* (blob/memory-store) ann
                              (:listing (listing/validate fixtures/settings {:kind "free" :title "Desk lamp" :category "home"
                                                                             :pickup "place:hall"}))
                              [] 1000)]
    (testing "a new post is queued, due a few minutes later, so edits made right after posting are in"
      (is (= [{:listing-id lamp :due-at (+ 1000 chat/delay-ms) :attempts 0}]
             (map #(select-keys % [:listing-id :due-at :attempts]) (chat/queued *db*)))))
    (testing "and leaves the queue with the post"
      (listing/delete! *db* (blob/memory-store) lamp ann)
      (is (empty? (chat/queued *db*))))))
