(ns s-exp.jumble.schema-test
  "The schema's constraints and indexes, the full-text index kept in step."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.fixtures :refer [*db* db-fixture]]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def now 1761650000000)

(defn- insert!
  [table row]
  (let [cols (keys row)]
    (:id (jdbc/execute-one! *db*
                            (into [(str "INSERT INTO "
                                        table
                                        " ("
                                        (str/join ", " (map name cols))
                                        ") VALUES ("
                                        (str/join ", " (repeat (count cols) "?"))
                                        ") RETURNING *")]
                                  (vals row))
                            {:builder-fn rs/as-unqualified-maps}))))

(defn- user!
  []
  (insert! "user"
           {:iss "https://dex.example.com" :sub (str (random-uuid))
            :email "a@example.com" :name "A"
            :created_at now :last_login_at now}))

(defn- listing!
  [overrides]
  (insert! "listing"
           (merge {:user_id (user!) :kind "free" :title "Desk lamp"
                   :category "home" :pickup_place "hall"
                   :created_at now :updated_at now}
                  overrides)))

(defn- rejected?
  [f]
  (try
    (f)
    false
    (catch java.sql.SQLException _
      true)))

(deftest listing-constraints-test
  (testing "a price is required for sales and only for sales"
    (is (rejected? #(listing! {:kind "sale"})))
    (is (rejected? #(listing! {:kind "free" :price_minor 500})))
    (is (rejected? #(listing! {:kind "lend" :price_minor 500})))
    (is (rejected? #(listing! {:kind "sale" :price_minor 0})))
    (is (integer? (listing! {:kind "sale" :price_minor 500}))))
  (testing "pickup is either a configured place or a city, never both or neither"
    (is (rejected? #(listing! {:pickup_place nil})))
    (is (rejected? #(listing! {:pickup_city "Lausanne"})))
    (is (rejected? #(listing! {:pickup_place nil :pickup_city ""})))
    (is (integer? (listing! {:pickup_place nil :pickup_city "Lausanne"}))))
  (testing "kind and status are closed sets"
    (is (rejected? #(listing! {:kind "swap"})))
    (is (rejected? #(listing! {:status "archived"}))))
  (testing "sold only applies to sales, given only to free items"
    (is (rejected? #(listing! {:kind "free" :status "sold"})))
    (is (rejected? #(listing! {:kind "sale" :price_minor 500 :status "given"})))
    (is (integer? (listing! {:kind "free" :status "given"}))))
  (testing "titles are 1 to 120 characters"
    (is (rejected? #(listing! {:title ""})))
    (is (rejected? #(listing! {:title (apply str (repeat 121 "x"))}))))
  (testing "STRICT tables reject values of the wrong type"
    (is (rejected? #(listing! {:kind "sale" :price_minor "cheap"}))))
  (testing "owner must exist"
    (is (rejected? #(listing! {:user_id 999})))))

(deftest setting-test
  (jdbc/execute! *db* ["INSERT INTO setting (key, value) VALUES ('currency', 'CHF')"])
  (is (rejected? #(jdbc/execute! *db* ["INSERT INTO setting (key, value) VALUES ('currency', 'EUR')"]))))

(deftest photo-constraints-test
  (let [listing-id (listing! {})
        photo! #(insert! "photo"
                         (merge {:listing_id listing-id :position 0
                                 :object_key (str (random-uuid))
                                 :width 1600 :height 1200}
                                %))]
    (photo! {:position 0})
    (testing "one photo per position, positions 0 to 4"
      (is (rejected? #(photo! {:position 0})))
      (is (rejected? #(photo! {:position 5})))
      (is (integer? (photo! {:position 4}))))
    (testing "photos go with their listing"
      (jdbc/execute! *db* ["DELETE FROM listing WHERE id = ?" listing-id])
      (is (= 0 (:n (jdbc/execute-one! *db* ["SELECT count(*) AS n FROM photo"])))))))

(deftest session-constraints-test
  (let [user-id (user!)]
    (insert! "session"
             {:token_hash (byte-array 32) :user_id user-id
              :created_at now :expires_at now})
    (testing "a user is unique per issuer and subject"
      (is (rejected? #(jdbc/execute! *db*
                                     ["INSERT INTO user (iss, sub, email, name, created_at, last_login_at)
                                            SELECT iss, sub, email, name, created_at, last_login_at
                                            FROM user WHERE id = ?"
                                      user-id]))))
    (testing "sessions go with their user"
      (jdbc/execute! *db* ["DELETE FROM user WHERE id = ?" user-id])
      (is (= 0 (:n (jdbc/execute-one! *db* ["SELECT count(*) AS n FROM session"])))))))

(defn- search
  [q]
  (mapv :id
        (jdbc/execute! *db*
                       ["SELECT rowid AS id FROM listing_fts
                                  WHERE listing_fts MATCH ? ORDER BY rowid"
                        q]
                       {:builder-fn rs/as-unqualified-maps})))

(defn- fts-consistent?
  []
  (try
    (jdbc/execute! *db* ["INSERT INTO listing_fts (listing_fts, rank) VALUES ('integrity-check', 1)"])
    true
    (catch java.sql.SQLException _
      false)))

(deftest full-text-index-test
  (let [lamp (listing! {:title "Desk lamp" :description "Warm light"})
        bike (listing! {:title "Vélo de ville" :description "Bicycle, 3 speeds"})]
    (testing "new listings are searchable by title and description"
      (is (= [lamp] (search "lamp")))
      (is (= [bike] (search "bicycle"))))
    (testing "prefix search"
      (is (= [bike] (search "bicyc*"))))
    (testing "diacritics are ignored"
      (is (= [bike] (search "velo"))))
    (testing "edits replace the indexed text"
      (jdbc/execute! *db* ["UPDATE listing SET title = 'Floor lamp' WHERE id = ?" lamp])
      (is (= [] (search "desk")))
      (is (= [lamp] (search "floor"))))
    (testing "status changes leave the index alone"
      (jdbc/execute! *db* ["UPDATE listing SET status = 'given' WHERE id = ?" lamp])
      (is (= [lamp] (search "floor"))))
    (testing "deleted listings leave the index"
      (jdbc/execute! *db* ["DELETE FROM listing WHERE id = ?" bike])
      (is (= [] (search "velo"))))
    (testing "index matches the table after all of the above"
      (is (fts-consistent?)))
    (testing "two- and three-letter prefixes, as typed while searching, have an index of their own"
      (is (str/includes? (:sql (jdbc/execute-one! *db*
                                                  ["SELECT sql FROM sqlite_master WHERE name = 'listing_fts'"]
                                                  {:builder-fn rs/as-unqualified-maps}))
                         "prefix = '2 3'")))))

(deftest message-schema-test
  (let [alice (user!)
        bob (user!)
        listing-id (listing! {})
        thread! #(insert! "thread"
                          (merge {:subject "Desk lamp" :listing_id listing-id :post_title "Desk lamp"
                                  :created_at now :last_message_at now}
                                 %))
        thread-id (thread! {})]
    (insert! "thread_participant" {:thread_id thread-id :user_id alice})
    (insert! "thread_participant" {:thread_id thread-id :user_id bob})
    (testing "messages have a body of 1 to 2000 characters"
      (is (integer? (insert! "message" {:thread_id thread-id :sender_id alice :body "Hi" :created_at now})))
      (is (rejected? #(insert! "message" {:thread_id thread-id :sender_id alice :body "" :created_at now})))
      (is (rejected? #(insert! "message"
                               {:thread_id thread-id :sender_id alice
                                :body (apply str (repeat 2001 "x")) :created_at now}))))
    (testing "subjects are 1 to 120 characters"
      (is (rejected? #(thread! {:subject ""}))))
    (testing "a person is in a thread once"
      (is (rejected? #(insert! "thread_participant" {:thread_id thread-id :user_id alice}))))
    (testing "removing the listing keeps the thread and its title"
      (jdbc/execute! *db* ["DELETE FROM listing WHERE id = ?" listing-id])
      (is (= {:listing_id nil :post_title "Desk lamp"}
             (jdbc/execute-one! *db*
                                ["SELECT listing_id, post_title FROM thread WHERE id = ?" thread-id]
                                {:builder-fn rs/as-unqualified-maps})))
      (is (= 1 (:n (jdbc/execute-one! *db* ["SELECT count(*) AS n FROM message"])))))))

(deftest notification-schema-test
  (let [alice (user!)
        bob (user!)
        thread-id (insert! "thread"
                           {:subject "Desk lamp" :listing_id (listing! {}) :post_title "Desk lamp"
                            :created_at now :last_message_at now})
        message-id (do (insert! "thread_participant" {:thread_id thread-id :user_id alice})
                       (insert! "message" {:thread_id thread-id :sender_id alice :body "Hi" :created_at now}))
        notification! #(jdbc/execute-one! *db*
                                          ["INSERT INTO notification (thread_id, user_id, message_id, due_at)
                                                  VALUES (?, ?, ?, ?)"
                                           thread-id
                                           %
                                           message-id
                                           now])
        flag #(:notify_messages (jdbc/execute-one! *db*
                                                   ["SELECT notify_messages FROM user WHERE id = ?" %]
                                                   {:builder-fn rs/as-unqualified-maps}))]
    (testing "people get message emails unless they turn them off"
      (is (= 1 (flag alice)))
      (is (rejected? #(jdbc/execute! *db* ["UPDATE user SET notify_messages = 2 WHERE id = ?" alice]))))
    (testing "only participants of a thread are notified about it"
      (is (rejected? #(notification! bob)))
      (insert! "thread_participant" {:thread_id thread-id :user_id bob})
      (is (some? (notification! bob))))
    (testing "one pending notification per thread and person"
      (is (rejected? #(notification! bob))))
    (testing "removing the thread removes its notifications"
      (jdbc/execute! *db* ["DELETE FROM thread WHERE id = ?" thread-id])
      (is (= 0 (:n (jdbc/execute-one! *db* ["SELECT count(*) AS n FROM notification"])))))))

(deftest report-schema-test
  (let [alice (user!)
        bob (user!)
        listing-id (listing! {})
        report! #(insert! "report"
                          (merge {:listing_id listing-id :listing_title "Desk lamp" :reporter_id bob
                                  :created_at now}
                                 %))]
    (testing "one open report per person and listing"
      (is (integer? (report! {})))
      (is (rejected? #(report! {}))))
    (testing "a resolved report says how, by whom and when, all or nothing"
      (is (rejected? #(jdbc/execute! *db* ["UPDATE report SET resolution = 'dismissed'"])))
      (jdbc/execute! *db* ["UPDATE report SET resolution = 'dismissed', resolved_by = ?, resolved_at = ?" alice now])
      (is (integer? (report! {})) "and the same person can report again"))
    (testing "reasons are bounded"
      (is (rejected? #(report! {:reporter_id alice :reason (apply str (repeat 1001 "x"))}))))
    (testing "reports outlive the listing"
      (jdbc/execute! *db* ["DELETE FROM listing WHERE id = ?" listing-id])
      (is (= #{nil}
             (set (map :listing_id
                       (jdbc/execute! *db*
                                      ["SELECT listing_id FROM report"]
                                      {:builder-fn rs/as-unqualified-maps}))))))))

(defn- plan
  "SQLite's plan for `sql`, its steps' details joined."
  [sql]
  (str/join "; "
            (map :detail
                 (jdbc/execute! *db*
                                [(str "EXPLAIN QUERY PLAN " sql) "a@example.com"]
                                {:builder-fn rs/as-unqualified-maps}))))

(deftest user-email-index-test
  (testing "finding accounts by address searches an index, not every account"
    (is (re-find #"USING (COVERING )?INDEX user_email" (plan "SELECT 1 FROM user WHERE email = ?")))
    (is (re-find #"USING (COVERING )?INDEX user_email"
                 (plan "SELECT id FROM user u WHERE u.email = ? AND u.deleted_at IS NULL")))))
