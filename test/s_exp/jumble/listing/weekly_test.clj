(ns s-exp.jumble.listing.weekly-test
  "The weekly email of what is new on the board: once a week to each member
  who has not turned it off, none when nothing is new."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.listing.weekly :as weekly]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.view.i18n :as i18n]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def ^:private day (* 24 60 60 1000))
(def ^:private week (* 7 day))

(defn- outbox
  []
  (jdbc/execute! *db*
                 ["SELECT recipient, subject, body FROM email_outbox ORDER BY id"]
                 {:builder-fn rs/as-unqualified-maps}))

(defn- clear-outbox! [] (jdbc/execute! *db* ["DELETE FROM email_outbox"]))

(defn- member!
  "Member `name`, joined at `at`."
  [name at]
  (:id (account/upsert-user! *db* {:iss "i" :sub name :email (str name "@example.com") :name name} at)))

(defn- post!
  [owner title at]
  (listing/create! *db*
                   (blob/memory-store)
                   owner
                   (:listing (listing/validate fixtures/settings
                                               {:kind "free" :title title :category "home"
                                                :pickup "place:hall"}))
                   []
                   at))

(defn- send! [at] (weekly/send-due! *db* fixtures/settings at))

(deftest weekly-test
  (let [ann (member! "ann" 0)
        bob (member! "bob" 0)
        lamp (post! bob "Lamp" (* 2 day))
        chair (post! bob "Chair" (* 3 day))
        _ (post! ann "Ann's own desk" (* 4 day))]
    (testing "nothing before a member's first week"
      (send! (- week 1))
      (is (= [] (outbox))))
    (testing "a week after joining, what others posted, the newest first"
      (send! week)
      (let [email (first (filter #(= "ann@example.com" (:recipient %)) (outbox)))]
        (is (= "This week on Jumble: 2 new listings" (:subject email)))
        (is (str/includes? (:body email)
                           (str "- Chair: http://localhost:8080/listings/"
                                chair
                                "\n"
                                "- Lamp: http://localhost:8080/listings/"
                                lamp)))
        (is (not (str/includes? (:body email) "Ann's own desk")))
        (is (str/includes? (:body email) "http://localhost:8080/settings") "where to turn it off")))
    (testing "not again within the week"
      (clear-outbox!)
      (send! (+ week day))
      (is (= [] (outbox))))
    (testing "nothing new, no email"
      (send! (* 2 week))
      (is (= [] (outbox))))
    (testing "the next week, only what is new since"
      (post! bob "Bike" (+ (* 2 week) day))
      (send! (* 3 week))
      (let [email (first (filter #(= "ann@example.com" (:recipient %)) (outbox)))]
        (is (= "This week on Jumble: 1 new listing" (:subject email)))
        (is (str/includes? (:body email) "Bike"))
        (is (not (str/includes? (:body email) "Lamp")))))))

(deftest settling-test
  (let [ann (member! "ann" 0)
        bob (member! "bob" 0)
        minute (* 60 1000)]
    (post! bob "Lamp" day)
    (testing "a listing posted a moment ago, maybe still being saved, waits for the next email"
      (post! bob "Chair" (- week minute))
      (send! week)
      (let [email (first (filter #(= "ann@example.com" (:recipient %)) (outbox)))]
        (is (str/includes? (:body email) "Lamp"))
        (is (not (str/includes? (:body email) "Chair")))))
    (testing "and is in it"
      (clear-outbox!)
      (send! (* 2 week))
      (is (str/includes? (:body (first (filter #(= "ann@example.com" (:recipient %)) (outbox)))) "Chair")))
    (is (some? ann))))

(deftest turned-off-test
  (let [ann (member! "ann" 0)
        bob (member! "bob" 0)]
    (post! bob "Lamp" day)
    (testing "on by default; off, no email"
      (is (true? (account/weekly-digest *db* ann)))
      (account/set-weekly-digest! *db* ann false)
      (send! week)
      (is (not-any? #(= "ann@example.com" (:recipient %)) (outbox))))))

(deftest not-for-test
  (let [ann (member! "ann" 0)
        bob (member! "bob" 0)
        cleo (member! "cleo" 0)]
    (post! bob "Lamp" day)
    (jdbc/execute! *db* ["UPDATE user SET deleted_at = 1 WHERE id = ?" cleo])
    (testing "an account being deleted, or no longer let in, gets none"
      (weekly/send-due! *db* (assoc fixtures/settings :allowed-emails ["bob@example.com" "cleo@example.com"]) week)
      (is (= [] (outbox))))
    (is (some? ann))))

(deftest many-test
  (let [ann (member! "ann" 0)
        bob (member! "bob" 0)]
    (doseq [i (range 23)]
      (post! bob (str "Thing " i) (+ day i)))
    (testing "twenty at most, then how many more, and where"
      (send! week)
      (let [body (:body (first (filter #(= "ann@example.com" (:recipient %)) (outbox))))]
        (is (= 20 (count (re-seq #"(?m)^- Thing" body))))
        (is (str/includes? body "Thing 22"))
        (is (not (str/includes? body "Thing 2:")))
        (is (str/includes? body "And 3 more: http://localhost:8080/listings"))))
    (is (some? ann))))

(deftest language-test
  (let [ann (member! "ann" 0)
        bob (member! "bob" 0)]
    (account/set-language! *db* ann "fr")
    (post! bob "Lamp" day)
    (testing "in the member's language"
      (send! week)
      (is (= (binding [i18n/*catalog* (i18n/catalog "fr")]
               (i18n/tn "This week on {app-name}: {n} new listing"
                        "This week on {app-name}: {n} new listings"
                        1
                        {:app-name "Jumble"}))
             (:subject (first (filter #(= "ann@example.com" (:recipient %)) (outbox)))))))))

(deftest failing-member-test
  (let [ann (member! "ann" 0)
        bob (member! "bob" 0)
        cleo (member! "cleo" 0)
        weekly-email weekly/weekly-email]
    (post! bob "Lamp" day)
    (testing "a member whose email cannot be written does not hold up the others"
      (with-redefs [weekly/weekly-email (fn [settings to listings n]
                                          (if (= "ann@example.com" to)
                                            (throw (ex-info "Unwritable" {}))
                                            (weekly-email settings to listings n)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed for 1 of 3" (send! week)) "the failure still told"))
      (is (= ["cleo@example.com"] (map :recipient (outbox)))))
    (is (some? [ann cleo]))))
