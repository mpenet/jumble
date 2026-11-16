(ns s-exp.jumble.demo-reset-test
  "The demo's nightly reset: visitors' accounts go, with all they left on the
  demo members' side; demo members and moderators stay as they were."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [demo-reset]
            [demo-seed]
            [next.jdbc :as jdbc]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.message.model :as message]
            [s-exp.jumble.moderation.model :as moderation]
            [s-exp.jumble.push.crypto :as crypto]
            [s-exp.jumble.push.model :as push]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.store.db :as db])
  (:import (java.util Base64)))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(defn- account!
  [email]
  (:id (account/upsert-user! *db* {:iss "i" :sub email :email email :name email} 0)))

(defn- talk!
  "`sender` writes to the owner of `listing`."
  [sender listing now]
  (message/start! *db* {:sender-id sender :listing-id (:id listing) :subject (:title listing) :body "Hello"
                        :now now}))

(defn- phone!
  "Turns notifications on for `user-id`, on a phone."
  [user-id]
  (let [b64 #(.encodeToString (.withoutPadding (Base64/getUrlEncoder)) ^bytes %)]
    (push/subscribe! *db* user-id (account/create-session! *db* user-id 0 fixtures/session-ttl)
                     {:endpoint "https://fcm.googleapis.com/fcm/send/phone"
                      :p256dh (b64 (crypto/public-bytes (:public (crypto/generate-keys))))
                      :auth (b64 (byte-array 16 (byte 7)))}
                     1)))

(defn- titles []
  (set (map :title (jdbc/execute! *db* ["SELECT title FROM listing"] db/opts))))

(defn- count-of [table]
  (:n (jdbc/execute-one! *db* [(str "SELECT count(*) AS n FROM " table)] db/opts)))

(deftest reset-test
  (let [ana (account! "ana.ribeiro@jumbl.ing")
        moderator (account! "Max@Example.com")
        eve (account! "eve@visitor.example.org")
        lamp (fixtures/listing! ana "Desk lamp")
        _ (fixtures/listing! moderator "Moderator's bike")
        _ (fixtures/listing! eve "Eve's sofa")]
    (phone! ana)
    (talk! moderator lamp 10)
    (talk! eve lamp 20)
    (listing/favorite! *db* eve (:id lamp) 30)
    (moderation/report! *db* {:listing-id (:id lamp) :reporter-id eve :reason "spam" :now 40 :alerts []})
    (demo-seed/mark-demo! *db* "jumbl.ing")
    (is (= 1 (demo-reset/clear-visitors! *db* (blob/memory-store) {:domain "jumbl.ing" :moderators #{"max@example.com"}
                                                          :now 50})))
    (testing "visitors' accounts are deleted, with their listings"
      (is (= [ana moderator] (map :id (jdbc/execute! *db* ["SELECT id FROM user WHERE deleted_at IS NULL ORDER BY id"]
                                               db/opts))))
      (is (= #{"Desk lamp" "Moderator's bike"} (titles))))
    (testing "and what they left with demo members: conversations, their notifications, reports"
      (is (= 1 (count-of "thread")))
      (is (= 1 (message/unread-count *db* ana)))
      (is (zero? (count-of "report")))
      (is (zero? (count-of "favorite"))))
    (testing "a demo member's notification of a visitor's message, not yet sent, goes too"
      (is (= 1 (count-of "push_delivery"))))
    (testing "nothing is left to reset the night after"
      (is (zero? (demo-reset/clear-visitors! *db* (blob/memory-store) {:domain "jumbl.ing" :moderators #{"max@example.com"}
                                                              :now 60}))))))

(deftest demo-only-test
  (let [eve (account! "eve@visitor.example.org")
        clear! #(demo-reset/clear-visitors! *db* (blob/memory-store) {:domain "jumbl.ing" :moderators #{} :now 50})]
    (testing "a community the demo seed did not fill is left alone"
      (is (thrown? clojure.lang.ExceptionInfo (clear!))))
    (testing "nor one it filled for another domain"
      (demo-seed/mark-demo! *db* "demo.example.org")
      (is (thrown? clojure.lang.ExceptionInfo (clear!))))
    (is (= [eve] (map :id (jdbc/execute! *db* ["SELECT id FROM user WHERE deleted_at IS NULL"] db/opts))))))
