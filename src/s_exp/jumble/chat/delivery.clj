(ns s-exp.jumble.chat.delivery
  "Announces the queued new posts to the community's chat channel through
  its webhook (Slack's, Mattermost's, Discord's), tried again while it fails."
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [s-exp.jumble.chat.message :as message]
            [s-exp.jumble.chat.model :as chat]
            [s-exp.jumble.email.outbox :as outbox]
            [s-exp.jumble.listing.choices :as choices]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.metrics :as metrics]
            [s-exp.jumble.store.db :as db]
            [s-exp.oda :as oda]))

(set! *warn-on-reflection* true)

(def ^:private announced
  (metrics/counter "jumble_chat_total" "New posts handled for the chat channel, by result: sent, failed or dropped."
                   (for [result ["sent" "failed" "dropped"]] {:result result})))

(def ^:private batch-size
  "Announcements sent in one delivery round, the rest in the next: chat
  services take about one a second on a webhook."
  20)

(def ^:private max-attempts
  "Failed sends before an announcement is given up on, a few hours on."
  8)

(def ^:private max-age
  "Posts older than this when due are not announced: seeds date theirs in
  the past, and after a long outage the news is old."
  (* 24 60 60 1000))

(defn- due
  "Announcements due at `now`, each `:needless` when its post is off the
  board, its owner's account deleted, or the post older than [[max-age]]."
  [db now]
  (jdbc/execute! db [(str "SELECT a.id, a.attempts, l.id AS listing_id, l.title, l.kind, l.price_minor,
                                  l.category, l.pickup_place, l.pickup_city,
                                  (l.status NOT IN " listing/on-board-sql " OR u.deleted_at IS NOT NULL
                                   OR l.created_at < ?) AS needless
                           FROM chat_announcement a
                           JOIN listing l ON l.id = a.listing_id
                           JOIN user u ON u.id = l.user_id
                           WHERE a.channel = 'webhook' AND a.due_at <= ?
                           ORDER BY a.due_at, a.id LIMIT ?")
                     (- now max-age) now batch-size]
                 db/opts))

(defn- done!
  [db id]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["DELETE FROM chat_announcement WHERE id = ?" id])))

(defn- failed!
  [db {:keys [id attempts]} now]
  (let [attempts (inc attempts)]
    (db/with-write-tx [tx db]
      (if (< attempts max-attempts)
        (jdbc/execute-one! tx ["UPDATE chat_announcement SET attempts = ?, due_at = ? WHERE id = ?"
                               attempts (outbox/retry-at attempts now) id])
        (jdbc/execute-one! tx ["DELETE FROM chat_announcement WHERE id = ?" id])))))

(defn- deliver!
  "Sends one announcement with `send`; true when the channel took it."
  [db send settings {:keys [id listing-id needless] :as pending} now]
  (if (= 1 needless)
    (do (done! db id)
        (metrics/inc! announced {:result "dropped"})
        false)
    (let [text (message/text settings (assoc pending :id listing-id))
          status (try (:status (send {:method :post
                                      :url (:chat-webhook-url settings)
                                      :headers {"content-type" "application/json"}
                                      :body (oda/write-str {:text text})}))
                      ;; Unreachable, or anything else: tried again, then given up on.
                      (catch Exception e
                        (log/warn e "Chat announcement not sent" {:listing listing-id})
                        nil))]
      (cond
        (and status (<= 200 status 299))
        (do (done! db id) (metrics/inc! announced {:result "sent"}) true)

        (or (nil? status) (= 429 status) (<= 500 status))
        (do (failed! db pending now) (metrics/inc! announced {:result "failed"}) false)

        ;; The webhook was deleted, or refuses: asking again would not help.
        :else
        (do (log/warn "Chat webhook refused an announcement" {:listing listing-id :status status})
            (done! db id)
            (metrics/inc! announced {:result "dropped"})
            false)))))

(defn deliver-due!
  "Announces the new posts due at `now` with `send` (a function of a
  request to its response), one by one while `running` returns true; one
  failing leaves the others. Without a webhook the queue is emptied.
  Returns how many went out."
  [db send settings now running]
  (if-not (:chat-webhook-url settings)
    (do (chat/drop-due! db "webhook" now)
        0)
    (if-let [pending (seq (due db now))]
      (let [settings (merge settings (choices/all db))]
        (outbox/deliver-each pending running #(deliver! db send settings % now) #(select-keys % [:id :listing-id])))
      0)))
