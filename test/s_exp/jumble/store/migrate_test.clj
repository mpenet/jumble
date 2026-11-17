(ns s-exp.jumble.store.migrate-test
  "Migrations: applied in order, rolled back on failure, and the bundled
  ones over existing data."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.ex :as ex]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.error :as-alias error]
            [s-exp.jumble.fixtures :refer [call-with-db-path]]
            [s-exp.jumble.store.migrate :as migrate]))

(def m1 {:name "001-a.sql" :sql "CREATE TABLE a (x INTEGER); INSERT INTO a VALUES (1);"})
(def m2 {:name "002-b.sql" :sql "CREATE TABLE b (y INTEGER);"})
(def broken {:name "002-broken.sql" :sql "CREATE TABLE c (z INTEGER); INSERT INTO missing VALUES (1);"})

(defn- tables
  [ds]
  (into #{}
        (map :name)
        (jdbc/execute! ds
                       ["SELECT name FROM sqlite_schema WHERE type = 'table'"]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(deftest migrate-test
  (call-with-db-path (fn [path]
                       (with-open [ds (db/datasource path)]
                         (testing "a fresh database is at version 0"
                           (is (= 0 (migrate/version ds))))

                         (testing "applies pending migrations in order"
                           (migrate/migrate! ds [m1])
                           (is (= 1 (migrate/version ds)))
                           (is (= #{"a"} (tables ds))))

                         (testing "applied migrations do not run again"
                           (migrate/migrate! ds [m1 m2])
                           (is (= 2 (migrate/version ds)))
                           (is (= #{"a" "b"} (tables ds)))
                           (is (= 1 (:n (jdbc/execute-one! ds ["SELECT count(*) AS n FROM a"])))))))))

(deftest failed-migration-is-rolled-back-test
  (call-with-db-path (fn [path]
                       (let [ds (db/datasource path)
                             e (try
                                 (migrate/migrate! ds [m1 broken])
                                 nil
                                 (catch clojure.lang.ExceptionInfo e
                                   e))]
                         (is (= "002-broken.sql" (:migration (ex-data e))))
                         (is (= ::error/migration-failed (ex/ex-type e)))
                         (testing "earlier migrations stay applied"
                           (is (= 1 (migrate/version ds))))
                         (testing "statements of the failed migration are undone"
                           (is (= #{"a"} (tables ds))))))))

(def parent-child
  {:name "001-parent-child.sql"
   :sql "CREATE TABLE parent (id INTEGER PRIMARY KEY);
         CREATE TABLE child (id INTEGER PRIMARY KEY,
                             parent_id INTEGER REFERENCES parent (id) ON DELETE CASCADE);
         INSERT INTO parent VALUES (1);
         INSERT INTO child VALUES (10, 1);"})

(deftest table-rebuild-keeps-referencing-rows-test
  (testing "SQLite's create/copy/drop/rename rebuild does not cascade deletes"
    (call-with-db-path (fn [path]
                         (with-open [ds (db/datasource path)]
                           (migrate/migrate! ds
                                             [parent-child
                                              {:name "002-rebuild-parent.sql"
                                               :sql "CREATE TABLE parent_new (id INTEGER PRIMARY KEY, name TEXT);
                                     INSERT INTO parent_new (id) SELECT id FROM parent;
                                     DROP TABLE parent;
                                     ALTER TABLE parent_new RENAME TO parent;"}])
                           (is (= 1 (:n (jdbc/execute-one! ds ["SELECT count(*) AS n FROM child"]))))
                           (testing "foreign keys are enforced again afterwards"
                             (is (thrown? java.sql.SQLException
                                          (jdbc/execute! ds ["INSERT INTO child VALUES (11, 99)"])))))))))

(deftest migration-leaving-dangling-references-fails-test
  (call-with-db-path (fn [path]
                       (let [ds (db/datasource path)
                             e (try
                                 (migrate/migrate! ds
                                                   [parent-child
                                                    {:name "002-orphan.sql"
                                                     :sql "DELETE FROM parent;"}])
                                 nil
                                 (catch clojure.lang.ExceptionInfo e
                                   e))]
                         (is (= "002-orphan.sql" (:migration (ex-data e))))
                         (is (= ::error/dangling-foreign-keys (ex/ex-type (ex-cause e))))
                         (is (= 1 (migrate/version ds)))
                         (is (= 1 (:n (jdbc/execute-one! ds ["SELECT count(*) AS n FROM parent"]))))))))

(deftest refuses-newer-schema-test
  (call-with-db-path (fn [path]
                       (with-open [ds (db/datasource path)]
                         (migrate/migrate! ds [m1 m2])
                         (let [e (try
                                   (migrate/migrate! ds [m1])
                                   nil
                                   (catch clojure.lang.ExceptionInfo e
                                     e))]
                           (is (re-find #"newer" (ex-message e)))
                           (is (= ::error/schema-too-new (ex/ex-type e))))))))

(deftest bundled-migrations-test
  (testing "every listed migration exists on the classpath"
    (is (every? (comp seq :sql) (migrate/migrations)))))

(deftest pickup-place-migration-test
  (testing "listings picked up at an office keep their place, and the either/or check still holds"
    (call-with-db-path
     (fn [path]
       (let [ds (db/datasource path)
             [m1 m2 & later] (migrate/migrations)]
         (migrate/migrate! ds [m1 m2])
         (jdbc/execute! ds ["INSERT INTO user (id, iss, sub, email, name, created_at, last_login_at)
                             VALUES (1, 'i', 's', 'a@example.com', 'A', 0, 0)"])
         (jdbc/execute! ds ["INSERT INTO listing (user_id, kind, title, description, category, pickup_office,
                                                  status, created_at, updated_at)
                             VALUES (1, 'free', 'Lamp', '', 'home', 'hall', 'active', 0, 0)"])
         (migrate/migrate! ds (into [m1 m2] later))
         (is (= {:pickup_place "hall" :pickup_city nil}
                (jdbc/execute-one! ds
                                   ["SELECT pickup_place, pickup_city FROM listing"]
                                   {:builder-fn rs/as-unqualified-lower-maps})))
         (is (thrown? Exception (jdbc/execute! ds ["UPDATE listing SET pickup_place = NULL"]))))))))

(deftest pooled-connections-keep-foreign-keys-test
  (testing "migrating does not leave a pooled connection with foreign keys off"
    (call-with-db-path
     (fn [path]
       (with-open [ds (db/datasource path)]
         (migrate/migrate! ds [m1 m2])
         (dotimes [_ 3]
           (is (= 1 (val (first (jdbc/execute-one! ds ["PRAGMA foreign_keys"])))))))))))

(deftest categories-and-places-migration-test
  (call-with-db-path
   (fn [path]
     (with-open [ds (db/datasource path)]
       (let [all (migrate/migrations)
             before (subvec all 0 8)
             q #(jdbc/execute! ds % {:builder-fn rs/as-unqualified-lower-maps})]
         (migrate/migrate! ds before)
         (jdbc/execute! ds ["INSERT INTO user (id, iss, sub, email, name, created_at, last_login_at)
                             VALUES (1, 'i', 's', 'a@example.com', 'A', 0, 0)"])
         (jdbc/execute! ds ["INSERT INTO listing (id, user_id, kind, title, description, category, pickup_place,
                                                  status, created_at, updated_at)
                             VALUES (1, 1, 'free', 'Lamp', '', 'garden', 'hall', 'active', 0, 0)"])
         (jdbc/execute! ds ["INSERT INTO listing (id, user_id, kind, title, description, category, pickup_city,
                                                  status, created_at, updated_at)
                             VALUES (2, 1, 'free', 'Desk lamp', '', 'home', 'Bern', 'active', 0, 0)"])
         (jdbc/execute! ds ["INSERT INTO report (listing_id, listing_title, reporter_id, created_at)
                             VALUES (1, 'Lamp', 1, 0)"])
         (migrate/migrate! ds all)
         (testing "the default categories come first, then those listings use, named by their id"
           (is (= ["electronics" "home" "kids" "sports" "books-media" "clothing" "tickets" "other" "garden"]
                  (mapv :id (q ["SELECT id FROM category ORDER BY position"]))))
           (is (= {:label "Furniture & home" :retired_at nil}
                  (first (q ["SELECT label, retired_at FROM category WHERE id = 'home'"]))))
           (is (= "garden" (:label (first (q ["SELECT label FROM category WHERE id = 'garden'"]))))))
         (testing "places are those listings use"
           (is (= [{:id "hall" :label "hall"}] (q ["SELECT id, label FROM place"]))))
         (testing "listings keep their data, and must name a known category and place"
           (is (= [{:id 1 :category "garden" :pickup_place "hall"} {:id 2 :category "home" :pickup_place nil}]
                  (q ["SELECT id, category, pickup_place FROM listing ORDER BY id"])))
           (is (thrown? Exception (jdbc/execute! ds ["UPDATE listing SET category = 'nope' WHERE id = 1"])))
           (is (thrown? Exception (jdbc/execute! ds ["UPDATE listing SET pickup_place = 'nope' WHERE id = 1"])))
           (is (thrown? Exception (jdbc/execute! ds ["UPDATE listing SET status = 'withdrawn' WHERE id = 1"]))))
         (testing "search, and the triggers on listings, still work"
           (is (= [{:rowid 1}] (q ["SELECT rowid FROM listing_fts WHERE listing_fts MATCH 'lamp' AND rowid = 1"])))
           (jdbc/execute! ds ["UPDATE listing SET title = 'Bicycle' WHERE id = 1"])
           (is (= [{:rowid 1}] (q ["SELECT rowid FROM listing_fts WHERE listing_fts MATCH 'bicycle'"])))
           (jdbc/execute! ds ["DELETE FROM listing WHERE id = 1"])
           (is (= [] (q ["SELECT rowid FROM listing_fts WHERE listing_fts MATCH 'bicycle'"])))
           (is (= "removed" (:resolution (first (q ["SELECT resolution FROM report"])))))))))))

(deftest listing-statuses-migration-test
  (call-with-db-path
   (fn [path]
     (with-open [ds (db/datasource path)]
       (let [all (migrate/migrations)
             q #(jdbc/execute! ds % {:builder-fn rs/as-unqualified-lower-maps})]
         (migrate/migrate! ds (subvec all 0 10))
         (jdbc/execute! ds ["INSERT INTO user (id, iss, sub, email, name, created_at, last_login_at)
                             VALUES (1, 'i', 's', 'a@example.com', 'A', 0, 0)"])
         (jdbc/execute! ds ["INSERT INTO listing (id, user_id, kind, title, description, category, pickup_city,
                                                  status, created_at, updated_at)
                             VALUES (1, 1, 'free', 'Lamp', '', 'home', 'Bern', 'given', 0, 0)"])
         (jdbc/execute! ds ["INSERT INTO favorite (user_id, listing_id, created_at) VALUES (1, 1, 0)"])
         (migrate/migrate! ds all)
         (testing "listings, their favorites and search survive"
           (is (= [{:id 1 :status "given"}] (q ["SELECT id, status FROM listing"])))
           (is (= [{:listing_id 1}] (q ["SELECT listing_id FROM favorite"])))
           (is (= [{:rowid 1}] (q ["SELECT rowid FROM listing_fts WHERE listing_fts MATCH 'lamp'"]))))
         (testing "reserved and expired are statuses now"
           (jdbc/execute! ds ["UPDATE listing SET status = 'reserved' WHERE id = 1"])
           (jdbc/execute! ds ["UPDATE listing SET status = 'expired' WHERE id = 1"])
           (is (thrown? Exception (jdbc/execute! ds ["UPDATE listing SET status = 'archived' WHERE id = 1"]))))
         (testing "the board reads its index"
           (is (some #(re-find #"listing_board" (:detail %))
                     (q ["EXPLAIN QUERY PLAN SELECT id FROM listing WHERE status IN ('active', 'reserved')
                          ORDER BY created_at DESC, id DESC LIMIT 25"])))))))))

(deftest allowlist-way-migration-test
  (call-with-db-path
   (fn [path]
     (with-open [ds (db/datasource path)]
       (let [all (migrate/migrations)
             q #(jdbc/execute! ds % {:builder-fn rs/as-unqualified-lower-maps})]
         (migrate/migrate! ds (subvec all 0 31))
         (jdbc/execute! ds ["INSERT INTO allowed_email (pattern, added_by, added_at, joined_by_link)
                             VALUES ('ann@example.org', NULL, 0, 1), ('*@partner.org', NULL, 0, 0)"])
         (migrate/migrate! ds all)
         (testing "addresses that joined by the link say so; the others were added by a moderator"
           (is (= [{:pattern "*@partner.org" :way "moderator"} {:pattern "ann@example.org" :way "link"}]
                  (q ["SELECT pattern, way FROM allowed_email ORDER BY pattern"]))))
         (testing "the way is one of those known"
           (is (thrown? Exception (jdbc/execute! ds ["UPDATE allowed_email SET way = 'other'"])))))))))
