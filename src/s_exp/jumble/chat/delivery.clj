(ns s-exp.jumble.chat.delivery
  "Announces the queued new posts on the community's chat channels: through
  its webhook (Slack's, Mattermost's, Discord's) and by its IRC bot, each
  tried again while it fails."
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
  (metrics/counter "jumble_chat_total" "New posts handled for the chat channels, by channel and result: sent, failed or dropped."
                   (for [channel chat/channels
                         result ["sent" "failed" "dropped"]]
                     {:channel channel :result result})))

(def ^:private max-attempts
  "Failed sends before an announcement is given up on, a few hours on."
  8)

(def ^:private max-age
  "Posts older than this when due are not announced: seeds date theirs in
  the past, and after a long outage the news is old."
  (* 24 60 60 1000))

(defn- due
  "`channel`'s announcements due at `now`, at most `limit`, each `:needless`
  when its post is off the board, its owner's account deleted, or the post
  older than [[max-age]]."
  [db channel now limit]
  (jdbc/execute! db [(str "SELECT a.id, a.attempts, l.id AS listing_id, l.title, l.kind, l.price_minor,
                                  l.category, l.pickup_place, l.pickup_city,
                                  (l.status NOT IN " listing/on-board-sql " OR u.deleted_at IS NOT NULL
                                   OR l.created_at < ?) AS needless
                           FROM chat_announcement a
                           JOIN listing l ON l.id = a.listing_id
                           JOIN user u ON u.id = l.user_id
                           WHERE a.channel = ? AND a.due_at <= ?
                           ORDER BY a.due_at, a.id LIMIT ?")
                     (- now max-age) channel now limit]
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
  "Announces one post on `channel` with `announce`, a function of the
  settings and the post returning :sent, :failed (tried again later),
  :refused (dropped) or :wait (left as it is). True when it went out."
  [db channel announce settings {:keys [id listing-id needless] :as pending} now]
  (let [outcome (if (= 1 needless) :refused (announce settings (assoc pending :id listing-id)))]
    (case outcome
      :sent (do (done! db id) (metrics/inc! announced {:channel channel :result "sent"}) true)
      :failed (do (failed! db pending now) (metrics/inc! announced {:channel channel :result "failed"}) false)
      :refused (do (done! db id) (metrics/inc! announced {:channel channel :result "dropped"}) false)
      :wait false)))

(defn- deliver-due!
  "Announces `channel`'s posts due at `now` with `announce` (see
  [[deliver!]]), at most `limit`, one by one while `running` returns true;
  one throwing leaves the others, one failing ends the round, as each try
  of a channel down may take seconds the loop's other work waits for.
  Without `announce`, the channel is not configured and its queue is
  emptied. Returns how many went out."
  [db channel announce settings now running limit]
  (if-not announce
    (do (chat/drop-due! db channel now)
        0)
    (if-let [pending (seq (due db channel now limit))]
      (let [settings (merge settings (choices/all db))
            failed (volatile! false)
            announce (fn [settings post]
                       (let [outcome (announce settings post)]
                         (when (= :failed outcome) (vreset! failed true))
                         outcome))]
        (outbox/deliver-each pending #(and (running) (not @failed)) #(deliver! db channel announce settings % now)
                             #(select-keys % [:id :listing-id])))
      0)))

(defn- webhook
  "Announces a post by posting its message as JSON with `send` (a function
  of a request to its response) to the webhook at `url`."
  [send url]
  (fn [settings {:keys [listing-id] :as post}]
    (let [status (try (:status (send {:method :post
                                      :url url
                                      :headers {"content-type" "application/json"}
                                      :body (oda/write-str {:text (message/text settings post)})}))
                      ;; Unreachable, or anything else: tried again, then given up on.
                      (catch Exception e
                        (log/warn e "Chat announcement not sent" {:listing listing-id})
                        nil))]
      (cond
        (and status (<= 200 status 299)) :sent
        (or (nil? status) (= 429 status) (<= 500 status)) :failed
        ;; The webhook was deleted, or refuses: asking again would not help.
        :else (do (log/warn "Chat webhook refused an announcement" {:listing listing-id :status status})
                  :refused)))))

(defn deliver-webhook!
  "Announces the posts due at `now` through the webhook of the settings, if
  any, with `send` (see [[webhook]]). Returns how many went out."
  [db send {:keys [chat-webhook-url] :as settings} now running]
  (deliver-due! db "webhook" (when chat-webhook-url (webhook send chat-webhook-url)) settings now running 20))

(defn deliver-irc!
  "Announces the posts due at `now` on IRC with `say`, the bot's, true once
  said and false while it is out of its channel, when they wait. A few
  each round: the bot says one line every two seconds. Returns how many
  went out."
  [db say settings now running]
  (deliver-due! db "irc" (when say #(if (say (message/irc-line %1 %2)) :sent :wait)) settings now running 5))
