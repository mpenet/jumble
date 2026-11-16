(ns s-exp.jumble.store.db
  "The SQLite database: pooled connections with the settings each needs,
  writes queued on one lock, and settings pinned at creation."
  (:require [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.ex :as ex]
            [s-exp.jumble.error :as error]
            [s-exp.jumble.store.migrate :as migrate])
  (:import (com.zaxxer.hikari HikariConfig HikariDataSource)
           (org.sqlite SQLiteErrorCode SQLiteException)
           (java.io Closeable)
           (java.util Properties)
           (java.util.concurrent TimeUnit)
           (java.util.concurrent.locks ReentrantLock)
           (javax.sql DataSource)))

(set! *warn-on-reflection* true)

(def opts
  "Query options: rows as maps of unqualified keys, column names dashed
  (`created_at` → `:created-at`)."
  {:builder-fn rs/as-unqualified-modified-maps
   :label-fn #(str/replace % "_" "-")})

(def ^:private connection-properties
  ;; sqlite-jdbc applies these when it opens each connection. Running them as
  ;; statements would only configure the connection that ran them.
  {:foreign_keys "true"
   :journal_mode "WAL"
   :synchronous "NORMAL"
   ;; Deferred transactions that read then write can fail with SQLITE_BUSY
   ;; without waiting on busy_timeout; IMMEDIATE takes the write lock at BEGIN.
   :transaction_mode "IMMEDIATE"})

;; A pooled DataSource carrying the lock that serializes write transactions.
;; SQLite allows one writer; a writer waiting on SQLite's busy handler sleeps
;; in native code, pinning its virtual thread's carrier. Waiting on a JVM lock
;; parks instead. Closing it closes the pool.
(deftype Database [^HikariDataSource ds ^ReentrantLock write-lock ^long write-lock-timeout-ms]
  Closeable
  (close [_] (.close ds))
  DataSource
  (getConnection [_] (.getConnection ds))
  (getConnection [_ user password] (.getConnection ds user password))
  (getLogWriter [_] (.getLogWriter ds))
  (setLogWriter [_ out] (.setLogWriter ds out))
  (getLoginTimeout [_] (.getLoginTimeout ds))
  (setLoginTimeout [_ seconds] (.setLoginTimeout ds seconds))
  (getParentLogger [_] (.getParentLogger ds))
  (unwrap [_ iface] (.unwrap ds iface))
  (isWrapperFor [_ iface] (.isWrapperFor ds iface)))

(defn datasource
  "A pooled DataSource for the SQLite file at `path`; close it when done."
  (^java.io.Closeable [path]
   (datasource path nil))
  (^java.io.Closeable [path {:keys [write-lock-timeout-ms busy-timeout-ms]
                             :or {write-lock-timeout-ms 5000 busy-timeout-ms 5000}}]
   (Database. (HikariDataSource.
               (doto (HikariConfig.)
                 (.setPoolName "jumble")
                 (.setJdbcUrl (str "jdbc:sqlite:" path))
                 (.setDataSourceProperties (doto (Properties.)
                                             (.putAll (update-keys connection-properties name))
                                             (.put "busy_timeout" (str busy-timeout-ms))))
                 ;; Readers run side by side under WAL; writers queue on the lock.
                 (.setMaximumPoolSize 8)
                 (.setMinimumIdle 1)
                 ;; Connect on first use: starting does not need the file yet.
                 (.setInitializationFailTimeout -1)))
              (ReentrantLock. true)
              write-lock-timeout-ms)))

(defn call-with-write-tx
  "Calls `(f tx)` inside a write transaction on `db`, holding its write lock.
  Throws when the lock is not acquired within the timeout, or when called
  from inside another write transaction on the same thread."
  [^Database db f]
  (let [^ReentrantLock lock (.-write-lock db)]
    (when (.isHeldByCurrentThread lock)
      (throw (ex/ex-info "Nested write transaction" ::error/nested-write-tx)))
    (when-not (.tryLock lock (.-write-lock-timeout-ms db) TimeUnit/MILLISECONDS)
      (throw (ex/ex-info "Timed out waiting for the database write lock"
                         ::error/write-lock-timeout
                         {:timeout-ms (.-write-lock-timeout-ms db)})))
    (try
      (jdbc/with-transaction [tx db]
        (f tx))
      (catch SQLiteException e
        ;; Another process (Litestream, say) held the database past
        ;; busy_timeout: as busy as a held lock.
        (if (#{SQLiteErrorCode/SQLITE_BUSY SQLiteErrorCode/SQLITE_BUSY_SNAPSHOT} (.getResultCode e))
          (throw (ex/ex-info "Timed out waiting for the database" ::error/write-lock-timeout nil e))
          (throw e)))
      (finally
        (.unlock lock)))))

(defmacro with-write-tx
  "Evaluates `body` with `tx` bound to a connection in a write transaction on
  `db`. All writes go through here so writers queue in the JVM."
  [[tx db] & body]
  `(call-with-write-tx ~db (fn [~tx] ~@body)))

(defn reachable?
  "Whether the database answers a query."
  [db]
  (some? (jdbc/execute-one! db ["SELECT 1"])))

(defn pin-settings!
  "Records each of `settings` (string keys and values) the first time it is
  seen, and throws when one differs from what was recorded: values the data
  depends on, such as the currency prices are stored in."
  [db settings]
  (with-write-tx [tx db]
    (doseq [[k v] settings]
      (let [recorded (:value (jdbc/execute-one! tx
                                                ["SELECT value FROM setting WHERE key = ?" k]
                                                opts))]
        (cond
          (nil? recorded)
          (jdbc/execute-one! tx ["INSERT INTO setting (key, value) VALUES (?, ?)" k v])

          (not= recorded v)
          (throw (ex/ex-info (str "Configured " k " differs from the one this database was created with")
                             ::error/setting-changed
                             {:setting k :recorded recorded :configured v})))))))

;; Started component carries `:datasource`; not a declared field so it does not
;; shadow the `datasource` fn inside the methods.
(defrecord Db [path pinned]
  component/Lifecycle
  (start [this]
    (let [ds (datasource path)]
      (migrate/migrate! ds (migrate/migrations))
      (pin-settings! ds pinned)
      (assoc this :datasource ds)))
  (stop [this]
    (some-> ^Closeable (:datasource this) .close)
    (assoc this :datasource nil)))

(defn component
  "`pinned`: settings recorded on first start that must not change, see
  [[pin-settings!]]."
  [{:keys! [path pinned]}]
  (map->Db {:path path :pinned pinned}))
