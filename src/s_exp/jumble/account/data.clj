(ns s-exp.jumble.account.data
  "What Jumble holds about a member: exported for them to download, or
  deleted with their account. Deleting removes their listings (photos
  included), favorites, sessions and pending emails, and scrubs their
  account, which stays only so that the messages and reports they wrote
  keep a sender: one without a name, shown as a former member."
  (:require [next.jdbc :as jdbc]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.listing.model :as listing]))

(set! *warn-on-reflection* true)

(defn- conversations
  [db user-id]
  (mapv (fn [{:keys [id subject post-title]}]
          {:subject subject
           :about post-title
           :messages (mapv (fn [{:keys [name body created-at]}] {:from name :body body :sent-at created-at})
                           (jdbc/execute! db ["SELECT u.name, m.body, m.created_at FROM message m
                                               JOIN user u ON u.id = m.sender_id
                                               WHERE m.thread_id = ? ORDER BY m.id"
                                              id]
                                          db/opts))})
        (jdbc/execute! db ["SELECT t.id, t.subject, t.post_title FROM thread t
                            JOIN thread_participant p ON p.thread_id = t.id
                            WHERE p.user_id = ? ORDER BY t.id"
                           user-id]
                       db/opts)))

(defn export
  "Everything about `user-id`: account, listings with links to their photos
  in `blobs` (valid from `now`), favorites, conversations (both sides: their
  correspondence), the reports they made, their saved searches and the
  devices they get notifications on."
  [db blobs user-id now]
  (let [account (jdbc/execute-one! db ["SELECT name, email, created_at, last_login_at, notify_messages, weekly_digest, language
                                        FROM user WHERE id = ?"
                                       user-id]
                                   db/opts)
        photo-url (listing/photo-urls blobs now)]
    {:account {:name (:name account)
               :email (:email account)
               :joined-at (:created-at account)
               :last-sign-in-at (:last-login-at account)
               :notify-messages (= 1 (:notify-messages account))
               :weekly-digest (= 1 (:weekly-digest account))
               :language (:language account)}
     :listings (for [row (jdbc/execute! db ["SELECT id, title, description, kind, price_minor, category, pickup_place,
                                                   pickup_city, status, created_at AS posted_at, updated_at
                                            FROM listing WHERE user_id = ? ORDER BY id"
                                           user-id]
                                       db/opts)]
                 (assoc row :photos (for [{:keys [object-key]} (jdbc/execute! db ["SELECT object_key FROM photo
                                                                                  WHERE listing_id = ? ORDER BY position"
                                                                                 (:id row)]
                                                                             db/opts)]
                                      (photo-url object-key "full"))))
     :favorites (jdbc/execute! db ["SELECT f.listing_id, l.title, f.created_at AS favorited_at FROM favorite f
                                    JOIN listing l ON l.id = f.listing_id
                                    WHERE f.user_id = ? ORDER BY f.created_at"
                                   user-id]
                               db/opts)
     :conversations (conversations db user-id)
     :reports (jdbc/execute! db ["SELECT listing_title, reason, created_at AS reported_at FROM report
                                  WHERE reporter_id = ? ORDER BY id"
                                 user-id]
                             db/opts)
     :saved-searches (jdbc/execute! db ["SELECT q, kind, category FROM saved_search WHERE user_id = ? ORDER BY id"
                                        user-id]
                                    db/opts)
     :devices (jdbc/execute! db ["SELECT created_at AS added_at FROM push_subscription WHERE user_id = ? ORDER BY id"
                                 user-id]
                             db/opts)}))

(defn delete-account!
  "Deletes the account of `user-id`, as described above. The account is
  marked deleted and signed out first, so nothing new is posted from it
  meanwhile; then its listings go, each with its photos; then the rest. A
  failure part way is resumed by deleting again, and before its owner signs
  in again (a deleted account never does: that starts a new one)."
  [db blobs user-id now]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["UPDATE user SET deleted_at = coalesce(deleted_at, ?) WHERE id = ?" now user-id])
    (jdbc/execute-one! tx ["DELETE FROM session WHERE user_id = ?" user-id]))
  (doseq [{:keys [id]} (jdbc/execute! db ["SELECT id FROM listing WHERE user_id = ?" user-id] db/opts)]
    (listing/delete! db blobs id user-id))
  (db/with-write-tx [tx db]
    (when-let [{:keys [email]} (jdbc/execute-one! tx ["SELECT email FROM user WHERE id = ? AND iss <> 'deleted'"
                                                      user-id]
                                                  db/opts)]
      (jdbc/execute-one! tx ["DELETE FROM favorite WHERE user_id = ?" user-id])
      (jdbc/execute-one! tx ["DELETE FROM saved_search WHERE user_id = ?" user-id])
      (jdbc/execute-one! tx ["DELETE FROM push_subscription WHERE user_id = ?" user-id])
      (jdbc/execute-one! tx ["DELETE FROM session WHERE user_id = ?" user-id])
      (jdbc/execute-one! tx ["DELETE FROM notification WHERE user_id = ?" user-id])
      ;; Unless another account still uses the address.
      (jdbc/execute-one! tx ["DELETE FROM email_outbox WHERE recipient = ?1 COLLATE NOCASE
                                AND NOT EXISTS (SELECT 1 FROM user WHERE email = ?1 COLLATE NOCASE AND id <> ?2
                                                                     AND deleted_at IS NULL)"
                             email user-id])
      (jdbc/execute-one! tx ["DELETE FROM email_sign_in WHERE email = ? COLLATE NOCASE" email])
      (jdbc/execute-one! tx ["UPDATE user SET name = '', email = '', iss = 'deleted', sub = CAST(id AS TEXT),
                                              notify_messages = 0, weekly_digest = 0, language = NULL,
                                              digest_sent_at = NULL, searches_checked_at = NULL, last_login_at = 0
                              WHERE id = ?"
                             user-id]))))

(def ^:private settle
  "How long a deletion may still be under way in the request that started
  it, before the background work takes it as stopped part way."
  (* 10 60 1000))

(defn finish-deletions!
  "Finishes the deletions that stopped part way (see [[delete-account!]]):
  accounts marked deleted a while ago, who they were still on them. Its
  owner never signs in again to resume it, nor does a moderator find it to
  remove again. Runs every delivery round; nil when there is none."
  [db blobs now]
  (seq (doall (for [{:keys [id]} (jdbc/execute! db ["SELECT id FROM user WHERE deleted_at <= ? AND iss <> 'deleted' LIMIT 10"
                                                    (- now settle)]
                                                db/opts)]
                (do (delete-account! db blobs id now) id)))))

(defn remove-member!
  "Removes the member with address `email`, as moderator `moderator-id`:
  its mailbox is recorded first, so they never sign in again, by any
  address of it (see [[account/removed?]]), then each of their accounts is
  deleted as by [[delete-account!]]."
  [db blobs {:keys [email moderator-id now]}]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["INSERT INTO removed_email (email, removed_by, removed_at) VALUES (?, ?, ?)
                            ON CONFLICT DO NOTHING"
                           (account/mailbox email) moderator-id now]))
  (doseq [{:keys [id]} (account/members-with-email db email)]
    (delete-account! db blobs id now)))
