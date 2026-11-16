(ns s-exp.jumble.moderation.model-test
  "Reports and their resolutions: removed, dismissed, or by the owner."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.fixtures :refer [*db* db-fixture listing! user!]]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.moderation.model :as moderation]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def ^:private alert {:to "dave@example.com" :subject "Listing reported" :text "Have a look"})

(defn- report!
  [reporter listing-id reason at]
  (moderation/report! *db*
                      {:listing-id listing-id :reporter-id reporter :reason reason :now at
                       :alerts [alert]}))

(defn- outbox
  []
  (mapv (juxt :recipient :subject)
        (jdbc/execute! *db*
                       ["SELECT recipient, subject FROM email_outbox ORDER BY id"]
                       {:builder-fn rs/as-unqualified-maps})))

(defn- resolutions
  []
  (mapv (juxt :listing_title :resolution :resolved_by)
        (jdbc/execute! *db*
                       ["SELECT listing_title, resolution, resolved_by FROM report ORDER BY id"]
                       {:builder-fn rs/as-unqualified-maps})))

(deftest validate-reason-test
  (is (= {:reason "Looks like spam"} (moderation/validate-reason "  Looks like spam ")))
  (is (= {:reason ""} (moderation/validate-reason nil)) "a reason is optional")
  (is (contains? (:errors (moderation/validate-reason (apply str (repeat 1001 "x")))) :reason)))

(deftest report-test
  (let [alice (user! "alice") bob (user! "bob") carol (user! "carol")
        lamp (:id (listing! alice "Desk lamp"))]
    (testing "a first report alerts the moderators"
      (is (= :new (report! bob lamp "Spam" 100)))
      (is (= [["dave@example.com" "Listing reported"]] (outbox))))
    (testing "reporting again changes nothing"
      (is (= :already (report! bob lamp "Spam!!" 200)))
      (is (= 1 (count (outbox)))))
    (testing "more people reporting it add to the report, without more alerts"
      (is (= :new (report! carol lamp "" 300)))
      (is (= 1 (count (outbox)))))
    (testing "not your own listing, nor one that is gone"
      (is (nil? (report! alice lamp "" 400)))
      (is (nil? (report! bob 999 "" 400))))
    (testing "moderators see open reports by listing, oldest first"
      (is (= [{:listing-id lamp :listing-title "Desk lamp" :owner-id alice :owner-name "alice" :owner-email "alice@example.com"
               :reports [{:reporter-name "bob" :reason "Spam" :created-at 100}
                         {:reporter-name "carol" :reason "" :created-at 300}]}]
             (moderation/open-reports *db*)))
      (is (= 1 (moderation/open-count *db*))))))

(deftest remove-test
  (let [alice (user! "alice") bob (user! "bob") dave (user! "dave")
        lamp (:id (listing! alice "Desk lamp"))
        notice {:to "alice@example.com" :subject "Your listing was removed" :text "Sorry"}]
    (report! bob lamp "Spam" 100)
    (testing "a moderator removes the listing, whoever owns it"
      (is (true? (moderation/remove-listing! *db*
                                             (blob/memory-store)
                                             {:listing-id lamp :moderator-id dave :now 200
                                              :notice notice})))
      (is (nil? (listing/by-id *db* lamp))))
    (testing "its reports are resolved and kept, and the owner is told"
      (is (= [["Desk lamp" "removed" dave]] (resolutions)))
      (is (= [["dave@example.com" "Listing reported"] ["alice@example.com" "Your listing was removed"]] (outbox)))
      (is (= 0 (moderation/open-count *db*))))
    (testing "removing it again does nothing"
      (is (false? (moderation/remove-listing! *db*
                                              (blob/memory-store)
                                              {:listing-id lamp :moderator-id dave :now 300
                                               :notice notice})))
      (is (= 2 (count (outbox)))))))

(deftest dismiss-test
  (let [alice (user! "alice") bob (user! "bob") dave (user! "dave")
        lamp (:id (listing! alice "Desk lamp"))]
    (report! bob lamp "Spam" 100)
    (testing "dismissing resolves the reports and keeps the listing"
      (is (= 1 (moderation/dismiss! *db* {:listing-id lamp :moderator-id dave :now 200})))
      (is (= [["Desk lamp" "dismissed" dave]] (resolutions)))
      (is (some? (listing/by-id *db* lamp))))
    (testing "a new report is a new case, and alerts again"
      (is (= :new (report! bob lamp "Still spam" 300)))
      (is (= 2 (count (outbox)))))))

(deftest remove-unreported-test
  (testing "a moderator removing a listing nobody reported leaves a record of it, with the reason"
    (let [alice (user! "alice") dave (user! "dave")
          lamp (:id (listing! alice "Desk lamp"))]
      (is (true? (moderation/remove-listing! *db*
                                             (blob/memory-store)
                                             {:listing-id lamp :moderator-id dave :now 200 :reason "Not allowed here"
                                              :notice {:to "alice@example.com" :subject "Removed" :text "Sorry"}})))
      (is (= [["Desk lamp" "removed" dave]] (resolutions)))
      (is (= "Not allowed here" (:reason (jdbc/execute-one! *db*
                                                            ["SELECT reason FROM report"]
                                                            {:builder-fn rs/as-unqualified-maps}))))
      (is (= 0 (moderation/open-count *db*))))))

(deftest owner-removal-test
  (testing "an owner removing a reported listing closes its reports: none stays open unseen"
    (let [alice (user! "alice") bob (user! "bob")
          lamp (:id (listing! alice "Desk lamp"))]
      (report! bob lamp "Spam" 100)
      (is (true? (listing/delete! *db* (blob/memory-store) lamp alice)))
      (is (= [["Desk lamp" "removed" alice]] (resolutions)))
      (is (= 0 (moderation/open-count *db*))))))
