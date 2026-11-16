(ns s-exp.jumble.email.outbox-test
  "The outbox: queued emails sent, retried, given up, and held for accounts
  being deleted."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* call-with-db-path db-fixture]]
            [s-exp.jumble.store.migrate :as migrate]
            [s-exp.jumble.email.outbox :as outbox]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def ^:private minute (* 60 1000))

(def ^:private t0 1761650000000)

(defn- enqueue!
  [to]
  (db/with-write-tx [tx *db*]
    (outbox/enqueue! tx {:to to :subject "Hello" :text "Body"} t0)))

(defn- deliver!
  [mailer at]
  (outbox/deliver-due! *db* mailer at (constantly true)))

(defn- pending
  "Emails still being tried."
  []
  (:n (jdbc/execute-one! *db* ["SELECT count(*) AS n FROM email_outbox WHERE given_up_at IS NULL"])))

(defn- given-up
  []
  (:n (jdbc/execute-one! *db* ["SELECT count(*) AS n FROM email_outbox WHERE given_up_at IS NOT NULL"])))

(def ^:private failing
  (fn [_] (throw (ex-info "SMTP down" {}))))

(deftest delivery-test
  (let [emails (atom [])
        mailer #(swap! emails conj %)]
    (enqueue! "a@example.com")
    (testing "queued emails go out once, counted"
      (let [sent (fixtures/counted "jumble_emails_total{result=\"sent\"}")]
        (is (= 1 (deliver! mailer t0)))
        (is (= 1.0 (- (fixtures/counted "jumble_emails_total{result=\"sent\"}") sent))))
      (is (= [{:to "a@example.com" :subject "Hello" :text "Body"}] @emails))
      (is (= 0 (deliver! mailer t0)))
      (is (= 0 (pending))))))

(deftest failure-test
  (let [emails (atom [])
        mailer #(swap! emails conj %)]
    (enqueue! "a@example.com")
    (testing "a failure is retried a minute later, not at once"
      (let [failed (fixtures/counted "jumble_emails_total{result=\"failed\"}")]
        (is (= 0 (deliver! failing t0)))
        (is (= 1.0 (- (fixtures/counted "jumble_emails_total{result=\"failed\"}") failed))))
      (is (= 0 (deliver! mailer t0)))
      (is (= 1 (deliver! mailer (+ t0 minute)))))
    (testing "and given up on after repeated failures, kept a week as such"
      (enqueue! "b@example.com")
      (doseq [m (range 0 1000 5)]
        (deliver! failing (+ t0 (* m minute))))
      (is (= 0 (pending)))
      (is (= 1 (given-up)))
      (let [emails (atom [])
            mailer #(swap! emails conj %)]
        (is (= 0 (deliver! mailer (+ t0 (* 2000 minute)))) "never tried again")
        (deliver! mailer (+ t0 (* 9 24 60 minute)))
        (is (= 0 (given-up)))))))

(deftest isolation-test
  (testing "one bad email does not hold back the others"
    (let [emails (atom [])
          mailer #(swap! emails conj %)]
      (enqueue! "not an address")
      (enqueue! "b@example.com")
      (let [picky (fn [email]
                    (if (= "not an address" (:to email))
                      (throw (ex-info "bad address" {}))
                      (mailer email)))]
        (is (= 1 (deliver! picky t0)))
        (is (= ["b@example.com"] (mapv :to @emails)))))))

(deftest stopping-test
  (enqueue! "a@example.com")
  (is (= 0 (outbox/deliver-due! *db* (constantly nil) t0 (constantly false))))
  (is (= 1 (pending))))

(deftest account-being-deleted-test
  (testing "emails to an account being deleted wait, for its deletion to drop them"
    (let [alice (fixtures/user! "alice")]
      (enqueue! "alice@example.com")
      (enqueue! "bob@example.com")
      ;; Deletion's first step alone, as while its listings are deleted.
      (jdbc/execute! *db* ["UPDATE user SET deleted_at = ? WHERE id = ?" t0 alice])
      (let [emails (atom [])
            mailer #(swap! emails conj %)]
        (deliver! mailer t0)
        (is (= ["bob@example.com"] (map :to @emails)))))))

(deftest busy-database-test
  (testing "an email sent while the database is busy is recorded once it frees up, not sent twice"
    (call-with-db-path
     (fn [path]
       (binding [*db* (db/datasource path {:write-lock-timeout-ms 50})]
         (migrate/migrate! *db* (migrate/migrations))
         (enqueue! "carol@example.com")
         (let [emails (atom [])
               sent #(swap! emails conj %)
               mailer (fn [email]
                        (let [holding (promise)]
                          (future (db/with-write-tx [_ *db*]
                                    (deliver holding true)
                                    (Thread/sleep 300)))
                          @holding
                          (sent email)))]
           (is (= 1 (deliver! mailer t0)))
           (is (= 0 (deliver! sent (+ t0 1000))))
           (is (= 1 (count @emails)))))))))

(deftest expiring-test
  (let [emails (atom [])
        mailer #(swap! emails conj (:to %))]
    (enqueue! "news@example.com")
    (db/with-write-tx [tx *db*]
      (outbox/enqueue! tx
                       {:to "link@example.com" :subject "Sign in" :text "Link" :expires-at (+ t0 (* 15 minute))}
                       (+ t0 minute)))
    (testing "an email of use for a while only goes first"
      (deliver! mailer (+ t0 (* 2 minute)))
      (is (= ["link@example.com" "news@example.com"] @emails)))
    (testing "and is dropped, not sent, once of no use"
      (reset! emails [])
      (db/with-write-tx [tx *db*]
        (outbox/enqueue! tx
                         {:to "late@example.com" :subject "Sign in" :text "Link" :expires-at (+ t0 (* 15 minute))}
                         t0))
      (deliver! mailer (+ t0 (* 16 minute)))
      (is (= [] @emails))
      (is (zero? (pending))))))
