(ns s-exp.jumble.listing.saved-searches-test
  "Saved searches and their daily news by email."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.listing.saved-searches :as saved]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def day (* 24 60 60 1000))

(defn- outbox
  []
  (jdbc/execute! *db* ["SELECT recipient, subject, body FROM email_outbox ORDER BY id"]
                 {:builder-fn rs/as-unqualified-maps}))

(defn- post!
  [owner title at & {:keys [kind category] :or {kind "free" category "home"}}]
  (listing/create! *db* (blob/memory-store) owner
                   (:listing (listing/validate fixtures/settings {:kind kind :title title :category category
                                                                  :pickup "place:hall" :price "10"}))
                   [] at))

(deftest save-test
  (let [ann (fixtures/user! "ann")]
    (testing "a search, its filters, or both"
      (is (:id (saved/save! *db* ann {:q "bike" :kind nil :category nil} 0)))
      (is (:id (saved/save! *db* ann {:q nil :kind "free" :category "home"} 0)))
      (is (= [{:q "bike" :kind nil :category nil} {:q nil :kind "free" :category "home"}]
             (map #(select-keys % [:q :kind :category]) (saved/by-user *db* ann)))))
    (testing "the same one twice is one"
      (saved/save! *db* ann {:q "bike" :kind nil :category nil} 0)
      (is (= 2 (count (saved/by-user *db* ann)))))
    (testing "nothing to search for is refused"
      (is (= {:error :empty} (saved/save! *db* ann {:q nil :kind nil :category nil} 0))))
    (testing "ten at most"
      (doseq [i (range 8)] (saved/save! *db* ann {:q (str "thing" i) :kind nil :category nil} 0))
      (is (= {:error :too-many} (saved/save! *db* ann {:q "one more" :kind nil :category nil} 0))))
    (testing "removed by their owner only"
      (let [{:keys [id]} (first (saved/by-user *db* ann))]
        (is (not (saved/remove! *db* (fixtures/user! "bob") id)))
        (is (saved/remove! *db* ann id))
        (is (= 9 (count (saved/by-user *db* ann))))))))

(deftest digest-test
  (let [ann (fixtures/user! "ann")
        bob (fixtures/user! "bob")]
    (saved/save! *db* ann {:q "bike" :kind nil :category nil} 0)
    (saved/save! *db* ann {:q nil :kind "lend" :category nil} 0)
    (post! bob "Old bike" 0)
    (testing "nothing new, no email"
      (saved/send-due! *db* fixtures/settings day)
      (is (= [] (outbox))))
    (post! bob "Red bike" (+ day 1))
    (post! bob "Drill" (+ day 2) :kind "lend")
    (post! ann "Ann's own bike" (+ day 3))
    (post! bob "Chair" (+ day 4))
    (testing "not more than once a day"
      (saved/send-due! *db* fixtures/settings (+ day 10))
      (is (= [] (outbox))))
    (testing "a day later: what is new for each search, not their own listings"
      (saved/send-due! *db* fixtures/settings (* 2 day))
      (let [[email & more] (outbox)]
        (is (nil? more))
        (is (= "ann@example.com" (:recipient email)))
        (is (= "New on Jumble: 2 listings for your saved searches" (:subject email)))
        (is (str/includes? (:body email) "Red bike"))
        (is (str/includes? (:body email) "Drill"))
        (is (not (str/includes? (:body email) "Old bike")))
        (is (not (str/includes? (:body email) "Ann's own")))
        (is (not (str/includes? (:body email) "Chair")))
        (is (str/includes? (:body email) "http://localhost:8080/my/searches"))))
    (testing "the same listings are not sent again"
      (saved/send-due! *db* fixtures/settings (* 4 day))
      (is (= 1 (count (outbox)))))
    (testing "listings that left the board are not sent"
      (let [id (post! bob "Blue bike" (+ (* 4 day) 1))]
        (listing/set-status! *db* id bob "given" (+ (* 4 day) 2)))
      (saved/send-due! *db* fixtures/settings (* 6 day))
      (is (= 1 (count (outbox)))))))

(deftest resave-test
  (let [ann (fixtures/user! "ann")
        bob (fixtures/user! "bob")]
    (saved/save! *db* ann {:q "bike" :kind nil :category nil} 0)
    (saved/send-due! *db* fixtures/settings day)
    (saved/remove! *db* ann (:id (first (saved/by-user *db* ann))))
    (post! bob "Old bike" (* 5 day))
    (testing "saving again after removing them all starts afresh"
      (saved/save! *db* ann {:q "bike" :kind nil :category nil} (* 60 day))
      (saved/send-due! *db* fixtures/settings (* 61 day))
      (is (= [] (outbox))))))

(deftest removed-member-test
  (let [ann (fixtures/user! "ann")
        bob (fixtures/user! "bob")
        bob-only (assoc fixtures/settings :allowed-emails ["bob@example.com"])]
    (saved/save! *db* ann {:q "bike" :kind nil :category nil} 0)
    (post! bob "Red bike" 1)
    (testing "someone no longer allowed in gets no digest"
      (saved/send-due! *db* bob-only day)
      (is (= [] (outbox))))
    (testing "and is not due again, so does not hold up the others"
      (is (pos? (:searches_checked_at (jdbc/execute-one! *db* ["SELECT searches_checked_at FROM user WHERE id = ?" ann]
                                                         {:builder-fn rs/as-unqualified-maps})))))))

(deftest member-deleted-meanwhile-test
  (let [ann (fixtures/user! "ann")
        bob (fixtures/user! "bob")
        by-user saved/by-user]
    (saved/save! *db* ann {:q "bike" :kind nil :category nil} 0)
    (post! bob "Red bike" 1)
    (testing "a member whose deletion started while their news was gathered gets none"
      (with-redefs [saved/by-user (fn [& args]
                                    ;; Deletion's first step, after the due members were read.
                                    (jdbc/execute! *db* ["UPDATE user SET deleted_at = 1 WHERE id = ?" ann])
                                    (apply by-user args))]
        (saved/send-due! *db* fixtures/settings day))
      (is (= [] (outbox))))))

(deftest failing-member-test
  (let [ann (fixtures/user! "ann")
        bob (fixtures/user! "bob")
        cleo (fixtures/user! "cleo")
        by-user saved/by-user]
    (saved/save! *db* ann {:q "bike" :kind nil :category nil} 0)
    (saved/save! *db* cleo {:q "bike" :kind nil :category nil} 0)
    (post! bob "Red bike" 1)
    (testing "a member whose news cannot be gathered does not hold up the others"
      (with-redefs [saved/by-user (fn [db id]
                                    (if (= ann id)
                                      (throw (ex-info "Unreadable search" {}))
                                      (by-user db id)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed for 1 of 2"
                              (saved/send-due! *db* fixtures/settings day)) "the failure still told"))
      (is (= ["cleo@example.com"] (map :recipient (outbox)))))))
