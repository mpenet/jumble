(ns s-exp.jumble.message.model
  "Messages between two people about a listing, grouped in threads. A
  thread's participants each track the last message they have read."
  (:require [next.jdbc :as jdbc]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.message.notification :as notification]
            [s-exp.jumble.push.model :as push]
            [s-exp.jumble.web.params :as params]))

(set! *warn-on-reflection* true)

(defn- body-error
  [body]
  (cond
    (empty? body) (i18n/t "Write a message.")
    (> (count body) 2000) (i18n/t "Keep the message to 2000 characters or fewer.")))

(defn validate-body
  "Validates a reply. Returns `{:body normalized}` or `{:errors {:body message}}`."
  [body]
  (let [body (params/trimmed body)]
    (if-let [error (body-error body)]
      {:errors {:body error}}
      {:body body})))

(defn validate
  "Validates a first message typed in a form. Returns `{:message normalized}`
  or `{:errors {field message}}`."
  [{:keys [subject body]}]
  (let [subject (params/trimmed subject)
        body (params/trimmed body)
        errors (cond-> {}
                 (not (<= 1 (count subject) 120)) (assoc :subject (i18n/t "Give it a subject of 120 characters or fewer."))
                 (body-error body) (assoc :body (body-error body)))]
    (if (seq errors)
      {:errors errors}
      {:message {:subject subject :body body}})))

(defn- add-message!
  "Adds a message from `sender-id`, who has then read the thread up to it,
  and schedules emailing the other participant about it, and notifying
  their devices. An email already pending keeps its time and covers this
  message too."
  [tx thread-id sender-id body now]
  (let [{:keys [id]} (jdbc/execute-one! tx ["INSERT INTO message (thread_id, sender_id, body, created_at)
                                             VALUES (?, ?, ?, ?) RETURNING id"
                                            thread-id sender-id body now]
                                        db/opts)]
    (jdbc/execute-one! tx ["UPDATE thread SET last_message_at = ? WHERE id = ?" now thread-id])
    (jdbc/execute-one! tx ["UPDATE thread_participant SET last_read_message_id = ?
                            WHERE thread_id = ? AND user_id = ?"
                           id thread-id sender-id])
    (jdbc/execute-one! tx ["INSERT INTO notification (thread_id, user_id, message_id, due_at)
                            SELECT thread_id, user_id, ?, ? FROM thread_participant
                            WHERE thread_id = ? AND user_id <> ?
                            ON CONFLICT (thread_id, user_id)
                            DO UPDATE SET message_id = excluded.message_id, attempts = 0"
                           id (+ now notification/grace-period) thread-id sender-id])
    (push/queue! tx thread-id sender-id id now)
    id))

(defn thread-about
  "Id of the thread about `listing-id` between the two users, if they have one."
  [connectable listing-id user-a user-b]
  (:id (jdbc/execute-one! connectable ["SELECT t.id FROM thread t
                                        JOIN thread_participant a ON a.thread_id = t.id AND a.user_id = ?
                                        JOIN thread_participant b ON b.thread_id = t.id AND b.user_id = ?
                                        WHERE t.listing_id = ?
                                        ORDER BY t.id LIMIT 1"
                                       user-a user-b listing-id]
                          db/opts)))

(defn start!
  "Sends a first message from `sender-id` to the owner of listing
  `listing-id`, continuing their thread about it when there is one. Returns
  the thread id, or nil when the listing is gone, not on offer, or the
  sender's own, or the sender's account is being deleted."
  [db {:keys [sender-id listing-id subject body now]}]
  (db/with-write-tx [tx db]
    (let [{owner :user-id :keys [status title]} (jdbc/execute-one! tx ["SELECT user_id, status, title FROM listing WHERE id = ?"
                                                                       listing-id]
                                                                   db/opts)]
      (when (and (listing/on-board status) (not= sender-id owner)
                 (jdbc/execute-one! tx ["SELECT 1 FROM user WHERE id = ? AND deleted_at IS NULL" sender-id]))
        (let [thread-id (or (thread-about tx listing-id sender-id owner)
                            (let [{:keys [id]} (jdbc/execute-one! tx ["INSERT INTO thread (subject, listing_id, post_title,
                                                                                         created_at, last_message_at)
                                                                     VALUES (?, ?, ?, ?, ?) RETURNING id"
                                                                      subject listing-id title now now]
                                                                  db/opts)]
                              (doseq [user-id [sender-id owner]]
                                (jdbc/execute-one! tx ["INSERT INTO thread_participant (thread_id, user_id) VALUES (?, ?)"
                                                       id user-id]))
                              id))]
          (add-message! tx thread-id sender-id body now)
          thread-id)))))

(defn- participant?
  [connectable thread-id user-id]
  (some? (jdbc/execute-one! connectable ["SELECT 1 FROM thread_participant WHERE thread_id = ? AND user_id = ?"
                                         thread-id user-id])))

(defn- with-former-member?
  "Whether someone in thread `thread-id` deleted their account."
  [db thread-id]
  (some? (jdbc/execute-one! db ["SELECT 1 FROM thread_participant p JOIN user u ON u.id = p.user_id
                                 WHERE p.thread_id = ? AND u.deleted_at IS NOT NULL"
                                thread-id])))

(defn reply!
  "Adds a message from `user-id` to thread `thread-id`. Returns its id, or
  nil when the user is not in the thread, or the other one deleted their
  account: nothing would reach them."
  [db thread-id user-id body now]
  (db/with-write-tx [tx db]
    (when (and (participant? tx thread-id user-id) (not (with-former-member? tx thread-id)))
      (add-message! tx thread-id user-id body now))))

(def ^:private post-title
  "The listing's current title, or the one it had when the thread started
  if it is gone. Selected as post_title, with the thread as t and its
  listing as l."
  "coalesce(l.title, t.post_title) AS post_title")

(defn thread
  "Thread `thread-id` as seen by `user-id`, with the other participant and
  the messages in order; nil when the user is not in it."
  [db thread-id user-id]
  (when (participant? db thread-id user-id)
    (let [row (jdbc/execute-one! db [(str "SELECT t.id, t.subject, t.listing_id, " post-title "
                                           FROM thread t LEFT JOIN listing l ON l.id = t.listing_id
                                           WHERE t.id = ?")
                                     thread-id]
                                 db/opts)
          other (jdbc/execute-one! db ["SELECT u.id, u.name, u.email, u.deleted_at FROM thread_participant p
                                        JOIN user u ON u.id = p.user_id
                                        WHERE p.thread_id = ? AND p.user_id <> ?"
                                       thread-id user-id]
                                   db/opts)]
      (assoc row
             :other other
             :messages (jdbc/execute! db ["SELECT m.id, m.sender_id, u.name AS sender_name, m.body, m.created_at
                                          FROM message m JOIN user u ON u.id = m.sender_id
                                          WHERE m.thread_id = ? ORDER BY m.id"
                                          thread-id]
                                      db/opts)))))

(defn mark-read!
  "Marks thread `thread-id` read for `user-id` up to message `up-to`, the
  latest one they were shown: messages arriving meanwhile stay unread.
  Never moves back, and writes only when there is something new."
  [db thread-id user-id up-to]
  (let [unread "WHERE thread_id = ? AND user_id = ? AND last_read_message_id < ?"]
    (when (jdbc/execute-one! db [(str "SELECT 1 FROM thread_participant " unread) thread-id user-id up-to])
      (db/with-write-tx [tx db]
        (jdbc/execute-one! tx [(str "UPDATE thread_participant SET last_read_message_id = ? " unread)
                               up-to thread-id user-id up-to])))))

(defn inbox
  "Threads of `user-id`, latest activity first, each with the other
  participant, the last message and how many messages are unread."
  [db user-id]
  (jdbc/execute! db [(str "SELECT t.id, t.subject, " post-title ", t.listing_id, t.last_message_at,
                             o.id AS other_id, o.name AS other_name, o.email AS other_email,
                             (SELECT body FROM message m WHERE m.thread_id = t.id
                              ORDER BY m.id DESC LIMIT 1) AS last_body,
                             (SELECT count(*) FROM message m WHERE m.thread_id = t.id
                              AND m.id > me.last_read_message_id AND m.sender_id <> me.user_id) AS unread
                      FROM thread_participant me
                      JOIN thread t ON t.id = me.thread_id
                      JOIN thread_participant op ON op.thread_id = t.id AND op.user_id <> me.user_id
                      JOIN user o ON o.id = op.user_id
                      LEFT JOIN listing l ON l.id = t.listing_id
                      WHERE me.user_id = ?
                      ORDER BY t.last_message_at DESC, t.id DESC")
                     user-id]
                 db/opts))

(defn unread-count
  "Messages from others that `user-id` has not read yet, over all threads."
  [db user-id]
  (:n (jdbc/execute-one! db ["SELECT count(*) AS n FROM thread_participant me
                              JOIN message m ON m.thread_id = me.thread_id
                                AND m.id > me.last_read_message_id AND m.sender_id <> me.user_id
                              WHERE me.user_id = ?"
                             user-id]
                         db/opts)))
