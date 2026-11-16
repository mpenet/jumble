(ns s-exp.jumble.store.db-test
  "The database: connection settings, writes queued on one lock, timeouts,
  pinned settings."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.ex :as ex]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.error :as-alias error]
            [s-exp.jumble.fixtures :refer [call-with-db-path]]
            [s-exp.jumble.store.migrate :as migrate])
  (:import (java.util.concurrent CountDownLatch)))

(defn- pragma
  [conn name]
  (val (first (jdbc/execute-one! conn [(str "PRAGMA " name)]))))

(deftest datasource-pragmas-test
  (call-with-db-path (fn [path]
                       (with-open [ds (db/datasource path)]
                         (testing "every connection gets the pragmas, not only the first one"
                           (dotimes [_ 2]
                             (with-open [conn (jdbc/get-connection ds)]
                               (is (= 1 (pragma conn "foreign_keys")))
                               (is (= "wal" (pragma conn "journal_mode")))
                               (is (= 1 (pragma conn "synchronous")) "NORMAL")
                               (is (= 5000 (pragma conn "busy_timeout"))))))))))

(deftest pooled-connections-test
  (call-with-db-path (fn [path]
                       (with-open [ds (db/datasource path)]
                         (testing "connections are reused rather than opened for every query"
                           (let [underlying #(with-open [conn (jdbc/get-connection ds)]
                                               (.unwrap ^java.sql.Connection conn org.sqlite.SQLiteConnection))]
                             (is (identical? (underlying) (underlying)))))))))

(deftest write-transactions-take-the-lock-upfront-test
  (call-with-db-path (fn [path]
                       (with-open [ds (db/datasource path)]
                         (jdbc/execute! ds ["CREATE TABLE t (x INTEGER)"])
                         (with-open [reader (jdbc/get-connection ds)
                                     writer (jdbc/get-connection ds)]
                           (jdbc/execute! reader ["PRAGMA busy_timeout = 0"])
                           (jdbc/with-transaction [tx writer]
                             (testing "a transaction holds the write lock before its first write"
                               (jdbc/execute! tx ["SELECT * FROM t"])
                               (is (thrown? java.sql.SQLException
                                            (jdbc/execute! reader ["BEGIN IMMEDIATE"]))))))
                         (jdbc/execute! ds ["INSERT INTO t VALUES (1)"])))))

(defn- start-thread
  ^Thread [f]
  (doto (Thread. ^Runnable f) (.start)))

(defn- parked?
  "True once `t` is parked (on a JVM lock), as opposed to RUNNABLE, which is
  how a thread busy-waiting inside SQLite's native code looks."
  [^Thread t]
  (loop [n 200]
    (cond
      (#{Thread$State/WAITING Thread$State/TIMED_WAITING} (.getState t)) true
      (zero? n) false
      :else (do (Thread/sleep 10) (recur (dec n))))))

(deftest writers-queue-in-the-jvm-test
  (call-with-db-path (fn [path]
                       (let [ds (db/datasource path)
                             held (CountDownLatch. 1)
                             release (CountDownLatch. 1)]
                         (jdbc/execute! ds ["CREATE TABLE t (x INTEGER)"])
                         (let [first-writer (start-thread
                                             #(db/with-write-tx [tx ds]
                                                (jdbc/execute! tx ["INSERT INTO t VALUES (1)"])
                                                (.countDown held)
                                                (.await release)))
                               _ (.await held)
                               second-writer (start-thread
                                              #(db/with-write-tx [tx ds]
                                                 (jdbc/execute! tx ["INSERT INTO t VALUES (2)"])))]
                           (testing "a second writer waits on the JVM lock, not inside SQLite"
                             (is (parked? second-writer)))
                           (.countDown release)
                           (.join first-writer)
                           (.join second-writer)
                           (testing "both writes land once the first one commits"
                             (is (= 2 (:n (jdbc/execute-one! ds ["SELECT count(*) AS n FROM t"]))))))))))

(deftest write-lock-timeout-test
  (call-with-db-path (fn [path]
                       (let [ds (db/datasource path {:write-lock-timeout-ms 50})
                             held (CountDownLatch. 1)
                             release (CountDownLatch. 1)
                             writer (start-thread #(db/with-write-tx [_ ds]
                                                     (.countDown held)
                                                     (.await release)))]
                         (.await held)
                         (try
                           (let [e (try
                                     (db/with-write-tx [_ ds]
                                       :unreachable)
                                     nil
                                     (catch clojure.lang.ExceptionInfo e
                                       e))]
                             (is (re-find #"write lock" (ex-message e)))
                             (is (= ::error/write-lock-timeout (ex/ex-type e))))
                           (finally
                             (.countDown release)
                             (.join writer)))))))

(deftest nested-write-tx-test
  (call-with-db-path (fn [path]
                       (with-open [ds (db/datasource path)]
                         (testing "a nested write transaction would wait on its own lock; it fails fast"
                           (let [e (try
                                     (db/with-write-tx [_ ds]
                                       (db/with-write-tx [_ ds]
                                         :unreachable))
                                     nil
                                     (catch clojure.lang.ExceptionInfo e
                                       e))]
                             (is (re-find #"[Nn]ested" (ex-message e)))
                             (is (= ::error/nested-write-tx (ex/ex-type e)))))))))

(deftest pinned-settings-test
  (call-with-db-path (fn [path]
                       (with-open [ds (db/datasource path)]
                         (migrate/migrate! ds (migrate/migrations))
                         (testing "recorded on first start, accepted while unchanged"
                           (db/pin-settings! ds {"currency" "CHF"})
                           (db/pin-settings! ds {"currency" "CHF"})
                           (is (= "CHF" (:value (jdbc/execute-one! ds
                                                                   ["SELECT value FROM setting WHERE key = 'currency'"]
                                                                   {:builder-fn rs/as-unqualified-maps})))))
                         (testing "a changed value refuses to start, naming both"
                           (let [e (try
                                     (db/pin-settings! ds {"currency" "EUR"})
                                     nil
                                     (catch clojure.lang.ExceptionInfo e
                                       e))]
                             (is (= {:type ::error/setting-changed :setting "currency" :recorded "CHF" :configured "EUR"} (ex-data e)))))))))

(deftest busy-from-outside-test
  (testing "a database held by another process (Litestream, say) is busy like a held write lock"
    (call-with-db-path
     (fn [path]
       (with-open [ds (db/datasource path {:busy-timeout-ms 50})
                   other (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" path))]
         (jdbc/execute! ds ["CREATE TABLE t (x INTEGER)"])
         (jdbc/execute! other ["BEGIN IMMEDIATE"])
         (let [e (try
                   (db/with-write-tx [tx ds]
                     (jdbc/execute! tx ["INSERT INTO t VALUES (1)"]))
                   nil
                   (catch clojure.lang.ExceptionInfo e
                     e))]
           (is (= ::error/write-lock-timeout (ex/ex-type e))))
         (jdbc/execute! other ["ROLLBACK"]))))))
