(ns s-exp.jumble.push.delivery
  "Sends the queued browser notifications through the browsers' push
  services, encrypted for each device and signed with the community's key."
  (:require [clojure.tools.logging :as log]
            [s-exp.jumble.account.model :as account]
            [next.jdbc :as jdbc]
            [s-exp.jumble.email.outbox :as outbox]
            [s-exp.jumble.message.view :as message-view]
            [s-exp.jumble.metrics :as metrics]
            [s-exp.jumble.page.assets :as assets]
            [s-exp.jumble.push.crypto :as crypto]
            [s-exp.jumble.push.model :as push]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.oda :as oda]))

(set! *warn-on-reflection* true)

(def ^:private sent
  (metrics/counter "jumble_push_total"
                   "Browser notifications handled, by result: sent, gone, failed or dropped."
                   (for [result ["sent" "gone" "failed" "dropped"]] {:result result})))

(def ^:private batch-size 100)

(def ^:private ttl
  "Seconds a push service keeps a notification for a device that is off."
  (str (* 24 60 60)))

(def ^:private max-attempts
  "Failed sends before a notification is given up on, a quarter of an hour
  on: by then the email has told them."
  5)

(defn- due
  "Notifications due at `now`, each `:needless` when there is no longer
  anything to tell, or nobody signed in on that device to tell it to."
  [db now]
  (jdbc/execute! db
                 ["SELECT d.id, d.attempts, d.subscription_id, s.endpoint, s.p256dh, s.auth,
                             m.thread_id, t.subject, sender.name AS sender_name, u.email, u.language,
                             (u.deleted_at IS NOT NULL OR sender.deleted_at IS NOT NULL OR p.user_id IS NULL
                              OR p.last_read_message_id >= m.id
                              OR ss.expires_at <= ? OR ss.created_at <= ?) AS needless
                      FROM push_delivery d
                      JOIN push_subscription s ON s.id = d.subscription_id
                      JOIN session ss ON ss.token_hash = s.session_hash
                      JOIN user u ON u.id = s.user_id
                      JOIN message m ON m.id = d.message_id
                      JOIN thread t ON t.id = m.thread_id
                      JOIN user sender ON sender.id = m.sender_id
                      LEFT JOIN thread_participant p ON p.thread_id = m.thread_id AND p.user_id = s.user_id
                      WHERE d.due_at <= ?
                      ORDER BY d.due_at, d.id LIMIT ?"
                  now
                  (- now account/max-session-age)
                  now
                  batch-size]
                 db/opts))

(defn- notification
  "What the device shows: who wrote, about what (not the message itself:
  lock screens are seen by others), and where tapping it leads. One per
  conversation, the latest replacing the earlier."
  [{:keys [base-url]} {:keys [thread-id subject sender-name language]}]
  (i18n/speaking language
    (oda/write-str {:title (i18n/tn "New message from {name}: {subject}"
                                    "New messages from {name}: {subject}"
                                    1
                                    {:name sender-name :subject subject})
                    :url (str base-url (message-view/thread-path thread-id))
                    :tag (str "thread-" thread-id)
                    :icon (str base-url (assets/url "icon-192.png"))})))

(defn- done!
  [db id]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["DELETE FROM push_delivery WHERE id = ?" id])))

(defn- failed!
  [db {:keys [id attempts]} now]
  (let [attempts (inc attempts)]
    (db/with-write-tx [tx db]
      (if (< attempts max-attempts)
        (jdbc/execute-one! tx
                           ["UPDATE push_delivery SET attempts = ?, due_at = ? WHERE id = ?"
                            attempts
                            (outbox/retry-at attempts now)
                            id])
        (jdbc/execute-one! tx ["DELETE FROM push_delivery WHERE id = ?" id])))))

(defn- deliver!
  "Sends one notification with `send`; true when the push service took it."
  [db send settings allowed keys {:keys [id subscription-id endpoint needless email] :as pending} now]
  (if (or (= 1 needless) (not (allowed email)))
    (do (done! db id)
        (metrics/inc! sent {:result "dropped"})
        false)
    (let [status (try
                   (:status (send {:method :post
                                   :url endpoint
                                   :headers {"content-encoding" "aes128gcm"
                                             "content-type" "application/octet-stream"
                                             "ttl" ttl
                                             "urgency" "normal"
                                             "authorization" (crypto/authorization keys endpoint (:base-url settings) now)}
                                   :body (crypto/encrypt pending (.getBytes ^String (notification settings pending) "UTF-8"))}))
                   ;; Unreachable, or anything else: tried again, then given up on.
                   (catch Exception e
                     (log/warn e "Push notification not sent" {:subscription subscription-id})
                     nil))]
      (cond
        (and status (<= 200 status 299))
        (do (done! db id) (metrics/inc! sent {:result "sent"}) true)

        ;; The device unsubscribed, or its browser was reset.
        (#{404 410} status)
        (do (db/with-write-tx [tx db]
              (jdbc/execute-one! tx ["DELETE FROM push_subscription WHERE id = ?" subscription-id]))
            (metrics/inc! sent {:result "gone"})
            false)

        ;; Refused (403) as for a clock or setup at fault, tried again too:
        ;; the device itself may be fine.
        (or (nil? status) (#{403 429} status) (<= 500 status))
        (do (when (= 403 status)
              (log/warn "Push service refused the community's signature" {:subscription subscription-id}))
            (failed! db pending now)
            (metrics/inc! sent {:result "failed"})
            false)

        :else
        (do (log/warn "Push service refused a notification" {:subscription subscription-id :status status})
            (done! db id)
            (metrics/inc! sent {:result "dropped"})
            false)))))

(defn deliver-due!
  "Sends the notifications due at `now` with `send` (a function of a request
  to its response), one by one while `running` returns true; one failing
  leaves the others. Those of messages read meanwhile, to accounts being
  deleted, to devices whose sign-in ended, or to someone no longer let in,
  are dropped. Returns how many went out."
  [db send settings now running]
  (if-let [pending (seq (due db now))]
    (let [keys (push/keys! db)
          allowed (account/allowed db (:owner settings))]
      (outbox/deliver-each pending running #(deliver! db send settings allowed keys % now) #(select-keys % [:id])))
    0))
