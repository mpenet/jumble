(ns s-exp.jumble.message.notifier-test
  "Emails about unread messages: when they are sent, postponed or dropped,
  and failures."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* call-with-db-path db-fixture listing! user!]]
            [s-exp.jumble.message.model :as message]
            [s-exp.jumble.message.notification :as notification]
            [s-exp.jumble.message.notifier :as notifier]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def ^:private minute (* 60 1000))

(def ^:private t0 1761650000000)

(defn- at
  [minutes]
  (+ t0 (* minutes minute)))

(defn- deliver!
  [mailer minutes]
  (notifier/deliver-due! *db* mailer fixtures/settings (at minutes) (constantly true)))

(defn- pending
  []
  (:n (jdbc/execute-one! *db* ["SELECT count(*) AS n FROM notification"])))

(defn- read-all!
  [thread-id user-id]
  (message/mark-read! *db* thread-id user-id (:id (peek (:messages (message/thread *db* thread-id user-id))))))

(defn- conversation
  "bob writes to alice about her desk lamp at t0."
  []
  (let [alice (user! "alice")
        bob (user! "bob")
        lamp (listing! alice "Desk lamp")]
    {:alice alice
     :bob bob
     :lamp-id (:id lamp)
     :thread-id (message/start! *db*
                                {:sender-id bob :listing-id (:id lamp) :subject "Desk lamp"
                                 :body "Still available?" :now t0})}))

(deftest delivery-test
  (let [{:keys [alice bob thread-id]} (conversation)
        emails (atom [])
        mailer #(swap! emails conj %)]
    (testing "nothing while the message may still be read in the app"
      (is (= 0 (deliver! mailer 4)))
      (is (empty? @emails)))
    (testing "an email once it stayed unread for 5 minutes"
      (is (= 1 (deliver! mailer 5)))
      (let [[{:keys [to subject text]}] @emails]
        (is (= "alice@example.com" to))
        (is (= "New message from bob: Desk lamp" subject))
        (is (str/includes? text "bob sent you a message about \"Desk lamp\""))
        (is (str/includes? text "> Still available?"))
        (is (str/includes? text (str "http://localhost:8080/messages/" thread-id)))
        (is (str/includes? text "http://localhost:8080/settings"))))
    (testing "only once"
      (is (= 0 (deliver! mailer 6)))
      (is (= 0 (pending))))
    (testing "the sender is never emailed about their own messages"
      (is (every? #(= "alice@example.com" (:to %)) @emails)))
    (testing "more messages within the hour wait for it to pass"
      (message/reply! *db* thread-id bob "Hello?" (at 10))
      (message/reply! *db* thread-id bob "Anyone?" (at 20))
      (is (= 0 (deliver! mailer 30)))
      (is (= 1 (deliver! mailer 65)))
      (let [{:keys [subject text]} (peek @emails)]
        (is (= "New messages from bob: Desk lamp" subject))
        (is (str/includes? text "bob sent you 3 messages about \"Desk lamp\". The latest:"))
        (is (str/includes? text "> Anyone?"))))
    (testing "messages read in the app are not emailed"
      (message/reply! *db* thread-id bob "Last call" (at 130))
      (read-all! thread-id alice)
      (is (= 0 (deliver! mailer 140)))
      (is (= 0 (pending)))
      (is (= 2 (count @emails))))))

(deftest replies-notify-the-other-side-test
  (let [{:keys [alice thread-id]} (conversation)
        emails (atom [])
        mailer #(swap! emails conj %)]
    (read-all! thread-id alice)
    (message/reply! *db* thread-id alice "Yes, come by" (at 1))
    (is (= 1 (deliver! mailer 6)))
    (is (= ["bob@example.com" "New message from alice: Desk lamp"]
           ((juxt :to :subject) (first @emails))))
    (is (= 0 (pending)) "alice read bob's message, so nothing goes to her")))

(deftest opt-out-test
  (let [{:keys [alice]} (conversation)
        emails (atom [])
        mailer #(swap! emails conj %)]
    (account/set-notify-messages! *db* alice false)
    (is (= 0 (deliver! mailer 5)))
    (is (empty? @emails))
    (is (= 0 (pending)))))

(deftest message-while-sending-test
  (testing "a message arriving while the email goes out is emailed later, not lost"
    (let [{:keys [bob thread-id]} (conversation)
          emails (atom [])
          sent #(swap! emails conj %)
          mailer (fn [email]
                   (message/reply! *db* thread-id bob "One more thing" (at 5))
                   (sent email))]
      (is (= 1 (deliver! mailer 5)))
      (is (= 1 (pending)))
      (is (= 0 (deliver! sent 30)))
      (is (= 1 (deliver! sent 65)))
      (is (str/includes? (:text (peek @emails)) "> One more thing")))))

(deftest failed-delivery-test
  (let [{:keys [bob thread-id]} (conversation)
        failing (fn [_] (throw (ex-info "SMTP down" {})))
        emails (atom [])
        mailer #(swap! emails conj %)]
    (testing "a failure is retried later, not at once"
      (is (= 0 (deliver! failing 5)))
      (is (= 1 (pending)))
      (is (= 0 (deliver! mailer 5)))
      (is (= 1 (deliver! mailer 7)))
      (is (= 0 (pending))))
    (testing "and given up on after repeated failures"
      (message/reply! *db* thread-id bob "Hello?" (at 100))
      (doseq [m (range 105 1000 5)]
        (deliver! failing m))
      (is (= 0 (pending))))))

(deftest header-injection-test
  (testing "line breaks in a subject cannot add email headers"
    (let [alice (user! "alice")
          bob (user! "bob")
          emails (atom [])
          mailer #(swap! emails conj %)]
      (message/start! *db*
                      {:sender-id bob :listing-id (:id (listing! alice "Desk lamp"))
                       :subject "Lamp\r\nBcc: someone@example.org" :body "Hi" :now t0})
      (deliver! mailer 5)
      (is (= "New message from bob: Lamp Bcc: someone@example.org" (:subject (first @emails)))))))

(deftest grace-period-test
  (testing "a message joining a postponed email still gets its 5 minutes to be read"
    (let [{:keys [bob thread-id]} (conversation)
          emails (atom [])
          mailer #(swap! emails conj %)]
      (is (= 1 (deliver! mailer 5)))
      (message/reply! *db* thread-id bob "Hello?" (at 50))
      (is (= 0 (deliver! mailer 55)))
      (message/reply! *db* thread-id bob "Still there?" (at 64))
      (is (= 0 (deliver! mailer 65)))
      (is (= 1 (deliver! mailer 69))))))

(deftest news-while-giving-up-test
  (testing "a message arriving as a long failing email gives up is still emailed"
    (let [{:keys [bob thread-id]} (conversation)
          emails (atom [])
          mailer #(swap! emails conj %)
          failing (fn [_]
                    (message/reply! *db* thread-id bob "News" (at 5))
                    (throw (ex-info "SMTP down" {})))]
      (jdbc/execute! *db* ["UPDATE notification SET attempts = 9"])
      (deliver! failing 5)
      (is (= 1 (pending)))
      (is (= 1 (deliver! mailer 30)))
      (is (str/includes? (:text (first @emails)) "> News")))))

(deftest removed-listing-test
  (testing "a conversation goes on, with emails, after its listing is removed"
    (let [{:keys [alice lamp-id]} (conversation)
          emails (atom [])
          mailer #(swap! emails conj %)]
      (listing/delete! *db* (blob/memory-store) lamp-id alice)
      (is (= 1 (deliver! mailer 5))))))

(deftest one-failure-does-not-stop-the-batch-test
  (let [{:keys [alice thread-id]} (conversation)
        carol (user! "carol")
        emails (atom [])
        mailer #(swap! emails conj %)
        decide notification/decide]
    (message/start! *db*
                    {:sender-id carol :listing-id (:id (listing! alice "Chair")) :subject "Chair"
                     :body "Hi" :now t0})
    (with-redefs [notification/decide (fn [db pending now]
                                        (if (= thread-id (:thread-id pending))
                                          (throw (ex-info "boom" {}))
                                          (decide db pending now)))]
      (is (= 1 (deliver! mailer 5))))
    (is (= ["New message from carol: Chair"] (mapv :subject @emails)))))

(deftest busy-database-test
  (testing "an email sent while the database is busy is recorded once it frees up, not sent twice"
    (call-with-db-path
     (fn [path]
       (binding [*db* (db/datasource path {:write-lock-timeout-ms 50})]
         (fixtures/prepare! *db*)
         (conversation)
         (let [emails (atom [])
               sent #(swap! emails conj %)
               mailer (fn [email]
                        (let [holding (promise)]
                          (future (db/with-write-tx [_ *db*]
                                    (deliver holding true)
                                    (Thread/sleep 300)))
                          @holding
                          (sent email)))]
           (is (= 1 (deliver! mailer 5)))
           (is (= 0 (deliver! sent 6)))
           (is (= 1 (count @emails)))))))))

(deftest stopping-test
  (testing "a stopping notifier leaves the rest of the batch for later"
    (conversation)
    (let [emails (atom [])
          mailer #(swap! emails conj %)]
      (is (= 0 (notifier/deliver-due! *db* mailer fixtures/settings (at 5) (constantly false))))
      (is (= 1 (pending))))))

(deftest removed-recipient-test
  (let [_ (conversation)
        emails (atom [])
        mailer #(swap! emails conj %)]
    (fixtures/allow-only! ["bob@example.com"])
    (testing "someone no longer allowed in is not emailed about their messages"
      (is (= 0 (deliver! mailer 5)))
      (is (empty? @emails))
      (is (= 0 (pending))))))

(deftest recipient-being-deleted-test
  (let [{:keys [alice]} (conversation)
        emails (atom [])
        mailer #(swap! emails conj %)]
    ;; Deletion's first step alone, as while alice's listings are deleted.
    (jdbc/execute! *db* ["UPDATE user SET deleted_at = ? WHERE id = ?" t0 alice])
    (testing "someone whose account is being deleted is not emailed about messages"
      (is (= 0 (deliver! mailer 5)))
      (is (empty? @emails))
      (is (= 0 (pending))))))

(deftest nothing-to-tell-test
  (let [{:keys [alice bob thread-id]} (conversation)]
    (testing "a notification whose thread or recipient is gone is dropped"
      (is (= {:drop :gone} (notification/decide *db* {:thread-id 999 :user-id alice :message-id 1} (at 10))))
      (is (= {:drop :gone} (notification/decide *db* {:thread-id thread-id :user-id 999 :message-id 1} (at 10)))))
    (testing "and one about a former member, who cannot be answered"
      (jdbc/execute! *db* ["UPDATE user SET deleted_at = ? WHERE id = ?" (at 1) bob])
      (is (= {:drop :former-member}
             (notification/decide *db* {:thread-id thread-id :user-id alice :message-id 1} (at 10)))))))
