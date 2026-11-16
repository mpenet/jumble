(ns demo-reset
  "Resets a demo Jumble, nightly: deletes the accounts of visitors, everyone
  but the demo members (`*@<domain>`) and the moderators, with what they
  left on the others' side, then seeds again what the demo members lost.

  Run against a deployment's own database and storage, with its settings
  in the environment and -Djumble.demo=true:

    java -Djumble.demo=true -cp jumble.jar:dev clojure.main -m demo-reset \\
      dev/demo/listings.edn jumbl.ing"
  (:require [clojure.string :as str]
            [demo-seed]
            [next.jdbc :as jdbc]
            [s-exp.jumble.account.data :as data]
            [s-exp.jumble.config :as config]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.store.migrate :as migrate]))

(set! *warn-on-reflection* true)

(defn clear-visitors!
  "Deletes the accounts of everyone but the demo members, at `domain`, and
  the `moderators` (addresses), as [[data/delete-account!]] does; then the
  conversations and reports any deleted account had a part in, which
  deleting an account keeps for the others. Returns how many accounts were
  deleted. Throws, deleting nothing, unless the demo seed marked this
  community as a demo at `domain` (see [[demo-seed/mark-demo!]])."
  [db blobs {:keys [domain moderators now]}]
  (when-not (= domain (:value (jdbc/execute-one! db
                                                 ["SELECT value FROM community_setting WHERE name = 'demo'"]
                                                 db/opts)))
    (throw (ex-info "Not a demo at this domain: refusing to delete accounts" {:domain domain})))
  (let [moderators (set (map str/lower-case moderators))
        kept? (fn [{:keys [email]}]
                (let [email (str/lower-case email)]
                  (or (str/ends-with? email (str "@" domain)) (contains? moderators email))))
        users (jdbc/execute! db
                             ["SELECT id, email FROM user WHERE iss <> 'deleted'"]
                             db/opts)
        {kept true visitors false} (group-by kept? users)
        kept (set (map :id kept))
        participants (fn [tx]
                       (jdbc/execute! tx
                                      ["SELECT thread_id, user_id FROM thread_participant"]
                                      db/opts))]
    (doseq [{:keys [id]} visitors]
      (data/delete-account! db blobs id now))
    (db/with-write-tx [tx db]
      (doseq [id (distinct (for [{:keys [thread-id user-id]} (participants tx)
                                 :when (not (kept user-id))]
                             thread-id))]
        (jdbc/execute-one! tx
                           ["DELETE FROM push_delivery WHERE message_id IN (SELECT id FROM message WHERE thread_id = ?)"
                            id])
        (jdbc/execute-one! tx ["DELETE FROM thread WHERE id = ?" id]))
      (doseq [{:keys [id reporter-id]} (jdbc/execute! tx ["SELECT id, reporter_id FROM report"] db/opts)
              :when (not (kept reporter-id))]
        (jdbc/execute-one! tx ["DELETE FROM report WHERE id = ?" id])))
    (count visitors)))

(defn -main
  [data-path domain]
  (when-not (= "true" (System/getProperty "jumble.demo"))
    (binding [*out* *err*]
      (println "Refusing to reset without -Djumble.demo=true: this deletes accounts."))
    (System/exit 1))
  (let [{:keys [db storage moderators]} (config/load-config)
        ds (db/datasource (:path db))]
    (migrate/migrate! ds (migrate/migrations))
    (println (clear-visitors! ds
                              (blob/s3-store storage)
                              {:domain domain :moderators moderators :now (System/currentTimeMillis)})
             "visitors' accounts deleted.")
    (demo-seed/-main data-path domain)))
