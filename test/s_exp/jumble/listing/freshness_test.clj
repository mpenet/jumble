(ns s-exp.jumble.listing.freshness-test
  "Freshness: owners asked whether listings are still available, and those
  left unanswered expiring."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]
            [s-exp.jumble.listing.freshness :as freshness]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.email.outbox :as outbox]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def day (* 24 60 60 1000))

(defn- outbox
  []
  (jdbc/execute! *db*
                 ["SELECT recipient, subject, body FROM email_outbox ORDER BY id"]
                 {:builder-fn rs/as-unqualified-maps}))

(defn- post!
  ([owner title at] (post! owner title at "free"))
  ([owner title at kind]
   (listing/create! *db*
                    (blob/memory-store)
                    owner
                    (:listing (listing/validate fixtures/settings
                                                {:kind kind :title title :category "home"
                                                 :pickup "place:hall"}))
                    []
                    at)))

(defn- sweep!
  [at]
  (freshness/sweep! *db* fixtures/settings at))

(defn- status-of [id] (:status (listing/by-id *db* id)))

(defn- send-emails!
  "Sends what the outbox holds at `at`, through `mailer` (a working one by
  default)."
  ([at] (send-emails! at (constantly nil)))
  ([at mailer] (outbox/deliver-due! *db* mailer at (constantly true))))

(deftest sweep-test
  (let [owner (fixtures/user! "ann")
        lamp (post! owner "Lamp" 0)
        desk (post! owner "Desk" (* 10 day))]
    (testing "nothing before 30 days"
      (sweep! (* 29 day))
      (is (= [] (outbox))))
    (testing "after 30 days on the board, its owner is asked once"
      (sweep! (* 30 day))
      (sweep! (* 31 day))
      (let [[email & more] (outbox)]
        (is (nil? more))
        (is (= "ann@example.com" (:recipient email)))
        (is (= "Is \"Lamp\" still available?" (:subject email)))
        (is (str/includes? (:body email) (str "http://localhost:8080/listings/" lamp)))))
    (testing "an answer within 15 days keeps it on the board"
      (is (listing/confirm-fresh! *db* lamp owner (* 40 day)))
      (sweep! (* 40 day))
      (send-emails! (* 40 day))
      (sweep! (* 46 day))
      (is (= "active" (status-of lamp))))
    (testing "without one, it leaves the board"
      (is (= "active" (status-of desk)) "asked at day 40")
      (sweep! (* 54 day))
      (is (= "active" (status-of desk)))
      (let [expired (fixtures/counted "jumble_listings_closed_total{outcome=\"expired\"}")]
        (sweep! (* 55 day))
        (is (= 1.0 (- (fixtures/counted "jumble_listings_closed_total{outcome=\"expired\"}") expired))))
      (is (= "expired" (status-of desk)))
      (is (= [] (map :title (filter #(= desk (:id %)) (:listings (listing/board *db* {:limit 10})))))))
    (testing "putting it back makes it fresh again"
      (is (listing/set-status! *db* desk owner "active" (* 56 day)))
      (sweep! (* 85 day))
      (is (= 0 (count (filter #(str/includes? (:subject %) "Desk") (outbox)))))
      (sweep! (* 86 day))
      (is (= "active" (status-of desk)))
      (is (= 1 (count (filter #(str/includes? (:subject %) "Desk") (outbox))))
          "asked again 30 days after"))))

(deftest wanted-reminder-test
  (testing "the owner of a wanted post is asked whether they are still looking"
    (post! (fixtures/user! "ann") "Child's bike" 0 "wanted")
    (sweep! (* 31 day))
    (let [[email] (outbox)]
      (is (= "Still looking for \"Child's bike\"?" (:subject email)))
      (is (str/includes? (:body email) "mark it as found")))))

(deftest activity-keeps-listings-fresh-test
  (let [owner (fixtures/user! "ann")
        lamp (post! owner "Lamp" 0)]
    (testing "an edit counts as an answer"
      (sweep! (* 30 day))
      (listing/update! *db*
                       (blob/memory-store)
                       lamp
                       owner
                       (:listing (listing/validate fixtures/settings
                                                   {:kind "free" :title "Brass lamp" :category "home"
                                                    :pickup "place:hall"}))
                       #{}
                       []
                       (* 35 day))
      (sweep! (* 50 day))
      (is (= "active" (status-of lamp)))
      (is (nil? (:reminded-at (listing/by-id *db* lamp)))))
    (testing "listings off the board are left alone"
      (listing/set-status! *db* lamp owner "given" (* 51 day))
      (sweep! (* 200 day))
      (is (= "given" (status-of lamp))))
    (testing "only owners confirm, and only listings on the board"
      (is (not (listing/confirm-fresh! *db* lamp owner (* 201 day))))
      (is (not (listing/confirm-fresh! *db* lamp (fixtures/user! "bob") (* 201 day)))))))

(def ^:private failing-mailer
  (fn [_] (throw (ex-info "SMTP down" {}))))

(deftest unsent-reminders-test
  (let [owner (fixtures/user! "ann")
        lamp (post! owner "Lamp" 0)]
    (sweep! (* 30 day))
    (testing "while the reminder has not gone out, the listing stays"
      (sweep! (* 50 day))
      (is (= "active" (status-of lamp))))
    (testing "a reminder the mail server never took is sent again"
      (doseq [m (range 0 (* 20 60) 5)]
        (send-emails! (+ (* 50 day) (* m 60 1000)) failing-mailer))
      (sweep! (* 51 day))
      (is (= 1 (count (filter #(nil? (:given_up_at %))
                              (jdbc/execute! *db*
                                             ["SELECT given_up_at FROM email_outbox"]
                                             {:builder-fn rs/as-unqualified-maps})))))
      (sweep! (* 52 day))
      (send-emails! (* 52 day)))
    (testing "and the listing expires 15 days after asking again"
      (sweep! (* 65 day))
      (is (= "active" (status-of lamp)))
      (sweep! (* 66 day))
      (is (= "expired" (status-of lamp))))))

(deftest daily-cap-test
  (let [owner (fixtures/user! "ann")]
    (dotimes [i 105]
      (post! owner (str "Thing " i) 0))
    (testing "at most 100 owners are asked a day: a backlog is spread out"
      (sweep! (* 30 day))
      (sweep! (+ (* 30 day) 1000))
      (is (= 100 (count (outbox))))
      (sweep! (* 31 day))
      (is (= 105 (count (outbox)))))))

(deftest removed-owner-test
  (let [lamp (post! (fixtures/user! "ann") "Lamp" 0)
        bob-only (assoc fixtures/settings :allowed-emails ["bob@example.com"])]
    (testing "an owner no longer allowed in is not asked"
      (freshness/sweep! *db* bob-only (* 30 day))
      (is (= [] (outbox))))
    (testing "and the listing leaves the board as one left unanswered"
      (freshness/sweep! *db* bob-only (* 44 day))
      (is (= "active" (status-of lamp)))
      (freshness/sweep! *db* bob-only (* 45 day))
      (is (= "expired" (status-of lamp))))))

(deftest owner-being-deleted-test
  (let [owner (fixtures/user! "ann")
        _ (post! owner "Lamp" 0)]
    ;; Deletion's first step alone, its listings not yet deleted.
    (jdbc/execute! *db* ["UPDATE user SET deleted_at = 1 WHERE id = ?" owner])
    (testing "an owner whose account is being deleted is not asked"
      (sweep! (* 30 day))
      (is (= [] (outbox))))))

(defn- give-up-reminders!
  "The mail server refusing every reminder queued, as for an address that no
  longer exists: the outbox gives them up."
  [at]
  (jdbc/execute! *db*
                 ["UPDATE email_outbox SET given_up_at = ? WHERE listing_id IS NOT NULL AND given_up_at IS NULL"
                  at]))

(deftest undeliverable-owner-test
  (let [owner (fixtures/user! "ann")
        lamp (post! owner "Lamp" 0)
        queued #(count (filter (fn [e] (str/includes? (:subject e) "Lamp")) (outbox)))]
    (sweep! (* 30 day))
    (testing "a reminder the mail server never took is sent again, twice"
      (give-up-reminders! (* 31 day))
      (sweep! (* 31 day))
      (is (= 1 (queued)))
      (give-up-reminders! (* 32 day))
      (sweep! (* 32 day))
      (is (= 1 (queued))))
    (testing "refused a third time, the owner counts as not answering: the listing expires 15 days after"
      (give-up-reminders! (* 33 day))
      (sweep! (* 33 day))
      (is (= 0 (queued)))
      (sweep! (* 46 day))
      (is (= "active" (status-of lamp)))
      (sweep! (* 47 day))
      (is (= "expired" (status-of lamp)))))
  (testing "news starts the count again"
    (let [owner (fixtures/user! "bob")
          desk (post! owner "Desk" 0)]
      (sweep! (* 30 day))
      (dotimes [i 3]
        (give-up-reminders! (+ (* 30 day) i 1))
        (sweep! (+ (* 30 day) i 1)))
      (is (listing/confirm-fresh! *db* desk owner (* 35 day)))
      (sweep! (* 65 day))
      (give-up-reminders! (* 66 day))
      (sweep! (* 66 day))
      (is (= 1 (count (filter #(str/includes? (:subject %) "Desk") (outbox))))
          "asked again after the first refusal"))))

(deftest lend-test
  (testing "a listing to lend is a standing offer: never asked about, never expired"
    (let [drill (post! (fixtures/user! "ann") "Drill" 0 "lend")]
      (doseq [d [30 45 90 400]]
        (sweep! (* d day)))
      (is (= [] (outbox)))
      (is (= "active" (status-of drill))))))

(deftest reminder-days-test
  (let [owner (fixtures/user! "ann")
        lamp (post! owner "Lamp" 0)]
    (testing "the delay is the community's setting, and the email says it"
      (freshness/sweep! *db* (assoc fixtures/settings :listing-reminder-days 60) (* 59 day))
      (is (= [] (outbox)))
      (freshness/sweep! *db* (assoc fixtures/settings :listing-reminder-days 60) (* 60 day))
      (is (str/includes? (:body (first (outbox))) "60 days ago")))
    (testing "0: owners never asked, listings never expire"
      (let [desk (post! owner "Desk" 0)
            off (assoc fixtures/settings :listing-reminder-days 0)]
        (doseq [d [30 60 100 1000]]
          (freshness/sweep! *db* off (* d day)))
        (is (= 1 (count (outbox))) "only the lamp's, from before")
        (is (= "active" (status-of desk)))
        (is (= "active" (status-of lamp)) "asked, but never expired once off")))))
