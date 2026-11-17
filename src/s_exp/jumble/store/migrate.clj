(ns s-exp.jumble.store.migrate
  "Database migrations: the SQL files under resources/migrations, applied in
  order, each once, by the database's user_version."
  (:require [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [s-exp.ex :as ex]
            [s-exp.jumble.error :as error])
  (:import (java.sql Connection)))

(set! *warn-on-reflection* true)

(def ^:private files
  "Migration files under resources/migrations, in application order. Append
  only: a file's position is its version."
  ["001-init.sql" "002-messages.sql" "003-pickup-place.sql"
   "004-notifications.sql" "005-moderation.sql"
   "006-email-sign-in.sql" "007-allowed-emails.sql" "008-reports-on-removal.sql"
   "009-categories-and-places.sql" "010-favorites.sql" "011-listing-statuses.sql"
   "012-listing-freshness.sql" "013-account-deletion.sql" "014-saved-searches.sql"
   "015-favorite-changes.sql" "016-outbox-follow-up.sql" "017-removed-members.sql"
   "018-user-email-index.sql" "019-reminder-give-ups.sql" "020-search-prefixes.sql"
   "021-object-deletion.sql" "022-community-settings.sql"
   "023-member-language.sql" "024-weekly-digest.sql" "025-wanted.sql"
   "026-expiring-emails.sql" "027-push.sql" "028-reminded-index.sql"
   "029-chat-announcements.sql" "030-chat-channels.sql"
   "031-join-link.sql"])

(defn migrations
  []
  (mapv (fn [file]
          {:name file
           :sql (slurp (io/resource (str "migrations/" file)))})
        files))

(defn version
  [connectable]
  (val (first (jdbc/execute-one! connectable ["PRAGMA user_version"]))))

(defn- apply-migration!
  "Runs migration number `i` (0-based) and bumps user_version in the same
  transaction, so a failure leaves neither. Skips it when another process
  applied it first.

  Foreign keys are off while it runs, as SQLite requires for table rebuilds:
  with them on, dropping a referenced table cascades its ON DELETE actions.
  The pragma is a no-op inside a transaction, hence set before it starts,
  and back on before the connection returns to the pool. References left
  dangling make the migration fail."
  [ds i {:keys [name sql]}]
  (try
    (with-open [^Connection conn (jdbc/get-connection ds)]
      (jdbc/execute! conn ["PRAGMA foreign_keys = OFF"])
      (try
        (jdbc/with-transaction [tx conn]
          (when (= i (version tx))
            (with-open [stmt (.createStatement tx)]
              (.executeUpdate stmt ^String sql))
            (when-let [violations (seq (jdbc/execute! tx ["PRAGMA foreign_key_check"]))]
              (throw (ex/ex-info "Migration leaves dangling foreign keys"
                                 ::error/dangling-foreign-keys
                                 {:violations violations})))
            (jdbc/execute! tx [(str "PRAGMA user_version = " (inc i))])))
        (finally
          (jdbc/execute! conn ["PRAGMA foreign_keys = ON"]))))
    (catch Exception e
      (throw (ex/ex-info (str "Migration failed: " name) ::error/migration-failed {:migration name} e)))))

(defn migrate!
  "Applies the `migrations` (maps of :name and :sql) not yet applied to `ds`.
  Throws when the database is at a version this code does not know."
  [ds migrations]
  (let [current (version ds)]
    (when (> current (count migrations))
      (throw (ex/ex-info "Database schema is newer than this code"
                         ::error/schema-too-new
                         {:version current :known (count migrations)})))
    (doseq [[i migration] (map-indexed vector migrations)
            :when (>= i current)]
      (apply-migration! ds i migration))))
