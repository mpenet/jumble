(ns s-exp.jumble.account.data-test
  "A member's data: exported for them, deleted with their account."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [s-exp.jumble.store.db :as db]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.account.data :as data]
            [s-exp.jumble.account.invite :as invite]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.listing.saved-searches :as saved]
            [s-exp.jumble.message.notifier :as notifier]
            [s-exp.jumble.message.model :as message]
            [s-exp.jumble.moderation.model :as moderation]
            [s-exp.jumble.email.outbox :as outbox]
            [s-exp.jumble.push.model :as push])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(defn- q
  [sql & params]
  (jdbc/execute! *db* (into [sql] params) {:builder-fn rs/as-unqualified-maps}))

(defn- png
  ^bytes []
  (with-open [in (io/input-stream (io/resource "images/transparent.png"))]
    (.readAllBytes in)))

(defn- scene
  "Ann, who posted a lamp with a photo, favorited Bob's desk, wrote to Bob
  about it, reported it, invited a friend; Bob, who answered. Returns the
  ids."
  [blobs]
  (let [ann (fixtures/user! "ann")
        bob (fixtures/user! "bob")
        lamp (listing/create! *db*
                              blobs
                              ann
                              (:listing (listing/validate fixtures/settings
                                                          {:kind "free" :title "Lamp" :category "home"
                                                           :pickup "place:hall"}))
                              (:photos (listing/prepare-photos [(png)]))
                              1)
        desk (:id (fixtures/listing! bob "Desk"))
        thread (message/start! *db* {:sender-id ann :listing-id desk :subject "Desk" :body "Is it solid?" :now 2})]
    (message/reply! *db* thread bob "Very." 3)
    (listing/favorite! *db* ann desk 4)
    (moderation/report! *db* {:listing-id desk :reporter-id ann :reason "Too nice" :now 5 :alerts []})
    (saved/save! *db* ann {:q "bike" :kind nil :category nil} 6)
    (push/subscribe! *db*
                     ann
                     (account/create-session! *db* ann 7 fixtures/session-ttl)
                     {:endpoint "https://fcm.googleapis.com/fcm/send/ann"
                      :p256dh "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM"
                      :auth "tBHItJI5svbpez7KI4CCXg"}
                     7)
    (invite/invite! *db*
                    {:member-id ann
                     :email "friend@gmail.com"
                     :now 8
                     :configured ["*@example.com"]
                     :email-for (fn [to] {:to to :subject "Invited" :text "Come"})})
    {:ann ann :bob bob :lamp lamp :desk desk :thread thread}))

(deftest export-test
  (let [blobs (blob/memory-store)
        {:keys [ann lamp desk]} (scene blobs)
        exported (data/export *db* blobs ann (Instant/ofEpochMilli 10))]
    (testing "who they are"
      (is (= {:name "ann" :email "ann@example.com" :notify-messages true :weekly-digest true :language nil}
             (select-keys (:account exported) [:name :email :notify-messages :weekly-digest :language]))))
    (testing "their listings, favorites and reports"
      (is (= [lamp] (map :id (:listings exported))))
      (is (= ["Lamp"] (map :title (:listings exported))))
      (is (= [((listing/photo-urls blobs (Instant/ofEpochMilli 10)) (:object-key (first (:photos (listing/by-id *db* lamp)))) "full")]
             (:photos (first (:listings exported))))
          "links to its photos")
      (is (= [{:listing-id desk :title "Desk"}] (map #(select-keys % [:listing-id :title]) (:favorites exported))))
      (is (= ["Too nice"] (map :reason (:reports exported))))
      (is (= [{:q "bike" :kind nil :category nil}] (:saved-searches exported)))
      (is (= [{:added-at 7}] (:devices exported)) "the devices notified, not their addresses")
      (is (= [{:email "friend@gmail.com" :sent-at 8}] (:invitations exported))))
    (testing "their conversations, both sides"
      (is (= [["ann" "Is it solid?"] ["bob" "Very."]]
             (map (juxt :from :body) (:messages (first (:conversations exported)))))))))

(deftest delete-test
  (let [blobs (blob/memory-store)
        {:keys [ann bob lamp desk thread]} (scene blobs)
        session (account/create-session! *db* ann 10 fixtures/session-ttl)]
    (outbox/enqueue! *db* {:to "ann@example.com" :subject "Hi" :text "Pending"} 10)
    (data/delete-account! *db* blobs ann 20)
    (testing "their listings and photos go"
      (is (nil? (listing/by-id *db* lamp)))
      (is (empty? @(:objects blobs))))
    (testing "and their favorites, sessions and pending emails"
      (is (= [] (q "SELECT * FROM favorite WHERE user_id = ?" ann)))
      (is (= [] (q "SELECT * FROM saved_search WHERE user_id = ?" ann)))
      (is (= [] (q "SELECT * FROM push_subscription WHERE user_id = ?" ann)))
      (is (= [] (q "SELECT * FROM invitation WHERE user_id = ?" ann)))
      (is (nil? (account/session-user *db* session 30)))
      (is (= [] (q "SELECT * FROM email_outbox WHERE recipient = 'ann@example.com'"))))
    (testing "what they wrote stays for the others, without a name (shown as a former member's)"
      (is (= ["" "Very."]
             (let [{:keys [messages]} (message/thread *db* thread bob)]
               [(:sender-name (first messages)) (:body (second messages))])))
      (is (= ["Is it solid?"] (map :body (q "SELECT body FROM message WHERE sender_id = ?" ann)))))
    (testing "nothing of who they were is left on the account"
      (is (= [{:name "" :email "" :iss "deleted"}]
             (q "SELECT name, email, iss FROM user WHERE id = ?" ann))))
    (testing "whom they invited may still join"
      (is ((account/allowed *db* ["*@example.com"]) "friend@gmail.com")))
    (testing "their reports stay, anonymous"
      (is (= 1 (count (q "SELECT * FROM report WHERE listing_id = ?" desk)))))
    (testing "the other side can no longer reply"
      (is (nil? (message/reply! *db* thread bob "Hello?" 40))))
    (testing "signing in again starts a new account"
      (is (not= ann (:id (account/upsert-user! *db* {:iss "i" :sub "ann" :email "ann@example.com" :name "ann"} 50)))))))

(deftest shared-address-test
  (let [blobs (blob/memory-store)
        {:keys [ann]} (scene blobs)]
    (account/upsert-user! *db* {:iss "other" :sub "ann" :email "Ann@example.com" :name "ann"} 10)
    (outbox/enqueue! *db* {:to "ann@example.com" :subject "Hi" :text "For the other account"} 10)
    (data/delete-account! *db* blobs ann 20)
    (testing "emails to an address another account still uses are kept"
      (is (= ["For the other account"] (map :body (q "SELECT body FROM email_outbox WHERE recipient = 'ann@example.com'")))))))

(deftest stopped-deletion-test
  (let [blobs (blob/memory-store)
        {:keys [ann lamp]} (scene blobs)
        minute (* 60 1000)]
    (account/set-language! *db* ann "fr")
    ;; Marked deleted and signed out, then stopped before its listings went.
    (jdbc/execute! *db* ["UPDATE user SET deleted_at = 20 WHERE id = ?" ann])
    (testing "left a while, as a deletion may still be under way"
      (data/finish-deletions! *db* blobs (+ 20 (* 5 minute)))
      (is (some? (listing/by-id *db* lamp))))
    (testing "then finished by the background work: listings, and who they were, gone"
      (data/finish-deletions! *db* blobs (+ 20 (* 11 minute)))
      (is (nil? (listing/by-id *db* lamp)))
      (is (= [{:name "" :email "" :iss "deleted" :language nil :weekly-digest 0 :digest-sent-at nil
               :searches-checked-at nil :last-login-at 0}]
             (jdbc/execute! *db*
                            ["SELECT name, email, iss, language, weekly_digest, digest_sent_at, searches_checked_at,
                                          last_login_at FROM user WHERE id = ?"
                             ann]
                            db/opts))))
    (testing "a finished one is left alone"
      (is (nil? (data/finish-deletions! *db* blobs (+ 20 (* 60 minute))))))))

(deftest deletion-is-final-test
  (let [blobs (blob/memory-store)
        {:keys [ann bob]} (scene blobs)]
    (data/delete-account! *db* blobs ann 20)
    (testing "a listing sent while the account was being deleted is refused"
      (is (nil? (listing/create! *db*
                                 blobs
                                 ann
                                 (:listing (listing/validate fixtures/settings
                                                             {:kind "free" :title "Late" :category "home"
                                                              :pickup "place:hall"}))
                                 []
                                 21)))
      (is (= [] (q "SELECT * FROM listing WHERE user_id = ?" ann))))
    (testing "nor a favorite, nor a conversation started meanwhile"
      (let [desk (:id (fixtures/listing! bob "Desk"))]
        (is (not (listing/favorite! *db* ann desk 22)))
        (is (= [] (q "SELECT * FROM favorite WHERE user_id = ?" ann)))
        (is (nil? (message/start! *db* {:sender-id ann :listing-id desk :subject "Desk" :body "Hi" :now 23})))))
    (testing "the other side is not emailed about a former member's unread message"
      (let [emails (atom [])
            mailer #(swap! emails conj %)]
        (notifier/deliver-due! *db* mailer fixtures/settings (* 60 60 1000) (constantly true))
        (is (= [] (filter #(= "bob@example.com" (:to %)) @emails)))
        (is (some? bob))))))
