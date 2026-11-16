(ns s-exp.jumble.chat.model
  "New posts queued to be announced to the community's chat channel, a few
  minutes after they are posted."
  (:require [next.jdbc :as jdbc]
            [s-exp.jumble.store.db :as db]))

(set! *warn-on-reflection* true)

(def delay-ms
  "How long after posting a post is announced: edits made right after
  posting, a typo or a photo, are in it."
  (* 5 60 1000))

(def channels
  "Where new posts are announced, each on its own: through the webhook, and
  on IRC. One not configured empties its queue."
  ["webhook" "irc"])

(defn enqueue!
  "Queues listing `listing-id`, posted at `now`, to be announced on each of
  [[channels]]; in the posting's transaction `tx`."
  [tx listing-id now]
  (doseq [channel channels]
    (jdbc/execute-one! tx ["INSERT INTO chat_announcement (channel, listing_id, due_at) VALUES (?, ?, ?)"
                           channel listing-id (+ now delay-ms)])))

(defn queued
  "The announcements waiting, oldest first."
  [db]
  (jdbc/execute! db ["SELECT * FROM chat_announcement ORDER BY due_at, id"] db/opts))

(defn drop-due!
  "Empties `channel`'s queue of what is due at `now`: it is not configured.
  Writes only when there is something to drop, as it runs every round."
  [db channel now]
  (when (jdbc/execute-one! db ["SELECT 1 FROM chat_announcement WHERE channel = ? AND due_at <= ? LIMIT 1" channel now])
    (db/with-write-tx [tx db]
      (jdbc/execute-one! tx ["DELETE FROM chat_announcement WHERE channel = ? AND due_at <= ?" channel now]))))
