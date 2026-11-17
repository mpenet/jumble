(ns s-exp.jumble.account.invite-test
  "Members inviting people by email, when moderators let them: a few a day
  each, kept in the allowlist as the member's invitation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [s-exp.jumble.account.invite :as invite]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]
            [s-exp.jumble.store.db :as db]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def ^:private day (* 24 60 60 1000))

(def ^:private configured ["*@example.com"])

(defn- invite!
  [member email now]
  (invite/invite! *db*
                  {:member-id member
                   :email email
                   :now now
                   :configured configured
                   :email-for (fn [to] {:to to :subject "Invited" :text "Come"})}))

(defn- outbox
  []
  (map :recipient (jdbc/execute! *db* ["SELECT recipient FROM email_outbox ORDER BY id"] db/opts)))

(defn- allowlist
  []
  (jdbc/execute! *db* ["SELECT pattern, added_by, way FROM allowed_email ORDER BY pattern"] db/opts))

(deftest open-test
  (testing "closed until moderators open it, and closed again"
    (is (not (invite/open? *db*)))
    (invite/set-open! *db* true)
    (is (invite/open? *db*))
    (invite/set-open! *db* false)
    (is (not (invite/open? *db*)))))

(deftest invite-test
  (let [ann (fixtures/user! "ann")]
    (testing "an invitation lets the address in, as the member's, and emails it"
      (is (= :sent (invite! ann "Neighbour@Gmail.com" 1000)))
      (is (= [{:pattern "neighbour@gmail.com" :added-by ann :way "invitation"}] (allowlist)))
      (is ((account/allowed *db* configured) "neighbour@gmail.com"))
      (is (= ["neighbour@gmail.com"] (outbox))))
    (testing "an address already let in is emailed, the allowlist left as it was"
      (is (= :sent (invite! ann "colleague@example.com" 2000)))
      (is (= ["neighbour@gmail.com"] (map :pattern (allowlist))))
      (is (= "colleague@example.com" (last (outbox)))))
    (testing "members and removed addresses are not emailed, though the member is told the same"
      (fixtures/user! "bob")
      (jdbc/execute-one! *db* ["INSERT INTO removed_email (email, removed_at) VALUES ('gone@gmail.com', 0)"])
      (is (= :sent (invite! ann "bob@example.com" 3000)))
      (is (= :sent (invite! ann "gone@gmail.com" 4000)))
      (is (= 2 (count (outbox))))
      (is (not ((account/allowed *db* configured) "gone@gmail.com"))))
    (testing "five a day each"
      (is (= :sent (invite! ann "fifth@gmail.com" 5000)))
      (is (= :limited (invite! ann "sixth@gmail.com" 6000)))
      (is (= :sent (invite! (fixtures/user! "carl") "sixth@gmail.com" 6000)) "another member's are their own")
      (is (= :sent (invite! ann "sixth@gmail.com" (+ 1000 day))) "a day after the first"))))

(deftest sent-test
  (let [ann (fixtures/user! "ann")]
    (fixtures/user! "bob")
    (invite! ann "neighbour@gmail.com" 1000)
    (invite! ann "bob@example.com" 2000)
    (account/email-user! *db* "neighbour@gmail.com" 3000)
    (invite! ann "later@gmail.com" 4000)
    (testing "a member's invitations, latest first, and whether each has joined since"
      (is (= [{:email "later@gmail.com" :sent-at 4000 :joined false}
              {:email "bob@example.com" :sent-at 2000 :joined false}
              {:email "neighbour@gmail.com" :sent-at 1000 :joined true}]
             (invite/sent *db* ann))))
    (testing "not another's"
      (is (= [] (invite/sent *db* (fixtures/user! "carl")))))))
