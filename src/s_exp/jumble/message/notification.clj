(ns s-exp.jumble.message.notification
  "Pending emails about unread messages, one per thread and recipient,
  written along with the messages. Each is sent, postponed or dropped when
  due: people are emailed only about messages they have not read, at most
  once an hour per thread, and not at all once they turned it off."
  (:require [next.jdbc :as jdbc]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.email.outbox :as outbox]))

(set! *warn-on-reflection* true)

(def ^:private minute (* 60 1000))

(def grace-period
  "How long a message may stay unread before its recipient is emailed."
  (* 5 minute))

(def ^:private resend-interval
  "Least time between two emails about the same thread."
  (* 60 minute))

(defn due
  "Up to `limit` notifications due at `now`, earliest first:
  `{:thread-id :user-id :message-id}`."
  [db now limit]
  (jdbc/execute! db
                 ["SELECT thread_id, user_id, message_id FROM notification
                      WHERE due_at <= ? ORDER BY due_at LIMIT ?"
                  now
                  limit]
                 db/opts))

(defn decide
  "What to do with `notification` at `now`:
  `{:send email}` with what the email needs, `{:postpone until}`, or
  `{:drop reason}` when there is nothing left to tell."
  [db {:keys [thread-id user-id message-id]} now]
  (let [{:keys [notify-messages notified-at last-read-message-id] :as row}
        (jdbc/execute-one! db
                           ["SELECT u.email, u.name, u.language, u.notify_messages, u.deleted_at, me.last_read_message_id, me.notified_at,
                                       t.subject, o.name AS other_name, o.deleted_at AS other_deleted_at
                                FROM thread_participant me
                                JOIN user u ON u.id = me.user_id
                                JOIN thread t ON t.id = me.thread_id
                                JOIN thread_participant op ON op.thread_id = t.id AND op.user_id <> me.user_id
                                JOIN user o ON o.id = op.user_id
                                WHERE me.thread_id = ? AND me.user_id = ?"
                            thread-id
                            user-id]
                           db/opts)
        ;; Only messages up to the one this notification covers: later
        ;; ones arrived after it was read and are left to a later email.
        unread (when row
                 (jdbc/execute! db
                                ["SELECT body, created_at FROM message
                                     WHERE thread_id = ? AND sender_id <> ? AND id > ? AND id <= ?
                                     ORDER BY id"
                                 thread-id
                                 user-id
                                 last-read-message-id
                                 message-id]
                                db/opts))
        ;; Not before the resend interval, nor before the latest message
        ;; had its grace period: it may have joined a postponed email.
        not-before (when (seq unread)
                     (max (+ (:created-at (peek unread)) grace-period)
                          (if notified-at (+ notified-at resend-interval) 0)))]
    (cond
      (nil? row) {:drop :gone}
      ;; Their account is being deleted.
      (:deleted-at row) {:drop :deleted}
      ;; Nobody to reply to.
      (:other-deleted-at row) {:drop :former-member}
      (zero? notify-messages) {:drop :opted-out}
      (empty? unread) {:drop :read}
      (< now not-before) {:postpone not-before}

      :else
      {:send {:thread-id thread-id
              :to (:email row)
              :language (:language row)
              :subject (:subject row)
              :sender-name (:other-name row)
              :unread (count unread)
              :latest (:body (peek unread))}})))

(defn- remove-covered!
  "Removes `notification` unless a newer message moved it on meanwhile."
  [tx {:keys [thread-id user-id message-id]}]
  (jdbc/execute-one! tx
                     ["DELETE FROM notification WHERE thread_id = ? AND user_id = ? AND message_id = ?"
                      thread-id
                      user-id
                      message-id]))

(defn drop!
  [db notification]
  (db/with-write-tx [tx db]
    (remove-covered! tx notification)))

(defn postpone!
  [db {:keys [thread-id user-id]} until]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx
                       ["UPDATE notification SET due_at = ? WHERE thread_id = ? AND user_id = ?"
                        until
                        thread-id
                        user-id])))

(defn sent!
  "Records that the email for `notification` went out at `now`."
  [db {:keys [thread-id user-id] :as notification} now]
  (db/with-write-tx [tx db]
    (remove-covered! tx notification)
    (jdbc/execute-one! tx
                       ["UPDATE thread_participant SET notified_at = ? WHERE thread_id = ? AND user_id = ?"
                        now
                        thread-id
                        user-id])))

(defn failed!
  "Records a failed attempt at `now`: retried and given up on as outbox
  emails are (see [[outbox/retry-at]]). Attempts count
  from the stored row, which a newer message resets. Returns `:retry`,
  `:give-up`, or nil when the notification is gone."
  [db {:keys [thread-id user-id]} now]
  (db/with-write-tx [tx db]
    (when-let [{:keys [attempts]} (jdbc/execute-one! tx
                                                     ["SELECT attempts FROM notification WHERE thread_id = ? AND user_id = ?"
                                                      thread-id
                                                      user-id]
                                                     db/opts)]
      (let [attempts (inc attempts)]
        (if (< attempts outbox/max-attempts)
          (do (jdbc/execute-one! tx
                                 ["UPDATE notification SET attempts = ?, due_at = ? WHERE thread_id = ? AND user_id = ?"
                                  attempts
                                  (outbox/retry-at attempts now)
                                  thread-id
                                  user-id])
              :retry)
          (do (jdbc/execute-one! tx ["DELETE FROM notification WHERE thread_id = ? AND user_id = ?" thread-id user-id])
              :give-up))))))
