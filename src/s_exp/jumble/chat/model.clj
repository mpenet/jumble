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

(defn enqueue!
  "Queues listing `listing-id`, posted at `now`, to be announced; in the
  posting's transaction `tx`."
  [tx listing-id now]
  (jdbc/execute-one! tx ["INSERT INTO chat_announcement (listing_id, due_at) VALUES (?, ?)"
                         listing-id (+ now delay-ms)]))

(defn queued
  "The announcements waiting, oldest first."
  [db]
  (jdbc/execute! db ["SELECT * FROM chat_announcement ORDER BY due_at, id"] db/opts))
