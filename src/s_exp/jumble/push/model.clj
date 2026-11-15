(ns s-exp.jumble.push.model
  "Browser notifications: the devices members turn them on for, the
  community's keys to sign with, and the notifications of new messages
  queued for each device (sent by [[s-exp.jumble.push.delivery]])."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [s-exp.jumble.push.crypto :as crypto]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.token :as token])
  (:import (java.net URI)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(def ^:private max-devices
  "Devices a member gets notifications on at most; the oldest make way."
  10)

(def ^:private push-services
  "Hosts of the browsers' push services (Chrome's and others on Google's,
  Firefox's, Safari's, Edge's): the only places Jumble posts to, so a
  subscription cannot make it reach anything else."
  ["fcm.googleapis.com" "updates.push.services.mozilla.com" "push.apple.com" "notify.windows.com"])

(defn keys!
  "The community's keys for signing to push services: made once, then kept."
  [db]
  (let [stored #(let [rows (jdbc/execute! % ["SELECT name, value FROM community_setting
                                               WHERE name IN ('push_private_key', 'push_public_key')"]
                                          db/opts)
                      by-name (into {} (map (juxt :name :value)) rows)]
                  (when (= 2 (count by-name))
                    {:private (by-name "push_private_key") :public (by-name "push_public_key")}))]
    (crypto/read-keys
     (or (stored db)
         (db/with-write-tx [tx db]
           (or (stored tx)
               (let [{:keys [private public] :as written} (crypto/write-keys (crypto/generate-keys))]
                 (jdbc/execute-one! tx ["INSERT INTO community_setting (name, value) VALUES ('push_private_key', ?)
                                         ON CONFLICT (name) DO UPDATE SET value = excluded.value" private])
                 (jdbc/execute-one! tx ["INSERT INTO community_setting (name, value) VALUES ('push_public_key', ?)
                                         ON CONFLICT (name) DO UPDATE SET value = excluded.value" public])
                 written)))))))

(defn- push-service?
  [endpoint]
  (try
    (let [uri (URI. endpoint)
          host (some-> (.getHost uri) str/lower-case)]
      (and (= "https" (.getScheme uri))
           (nil? (.getUserInfo uri))
           (#{-1 443} (.getPort uri))
           (some #(or (= host %) (str/ends-with? host (str "." %))) push-services)))
    (catch Exception _ false)))

(defn- decoded
  ^bytes [s]
  (try (.decode (Base64/getUrlDecoder) ^String s)
       (catch Exception _ (byte-array 0))))

(defn- valid?
  "Whether `subscription` is one a browser gives: a push service's address,
  its P-256 key and 16-byte secret."
  [{:keys [endpoint p256dh auth]}]
  (and (string? endpoint) (string? p256dh) (string? auth)
       (< (count endpoint) 2000)
       (push-service? endpoint)
       (crypto/usable-key? (decoded p256dh))
       (= 16 (alength (decoded auth)))))

(defn subscribe!
  "Turns notifications on for `user-id`, signed in with session `token`, on
  the device of browser `subscription` (`:endpoint`, `:p256dh`, `:auth`),
  until they sign out there. A device subscribing again is theirs from then
  on: what was queued for whoever had it is dropped. Past [[max-devices]],
  their oldest stops. False when it is not a subscription to a push
  service."
  [db user-id token {:keys [endpoint p256dh auth] :as subscription} now]
  (boolean
   (when (valid? subscription)
     (db/with-write-tx [tx db]
       (jdbc/execute-one! tx ["DELETE FROM push_delivery WHERE subscription_id IN
                                 (SELECT id FROM push_subscription WHERE endpoint = ? AND user_id <> ?)"
                              endpoint user-id])
       (jdbc/execute-one! tx ["INSERT INTO push_subscription (user_id, session_hash, endpoint, p256dh, auth, created_at)
                               VALUES (?, ?, ?, ?, ?, ?)
                               ON CONFLICT (endpoint) DO UPDATE SET user_id = excluded.user_id,
                                 session_hash = excluded.session_hash, p256dh = excluded.p256dh, auth = excluded.auth"
                              user-id (token/digest token) endpoint p256dh auth now])
       (jdbc/execute-one! tx ["DELETE FROM push_subscription WHERE user_id = ? AND id NOT IN
                                 (SELECT id FROM push_subscription WHERE user_id = ? ORDER BY id DESC LIMIT ?)"
                              user-id user-id max-devices]))
     true)))

(defn unsubscribe!
  "Turns notifications off on the device subscribed with `endpoint`."
  [db user-id endpoint]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["DELETE FROM push_subscription WHERE user_id = ? AND endpoint = ?" user-id endpoint])))

(defn remove!
  "Turns notifications off on `user-id`'s device `id`."
  [db user-id id]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["DELETE FROM push_subscription WHERE user_id = ? AND id = ?" user-id id])))

(defn endpoint-key
  "What tells a device's subscription apart without giving its address."
  [endpoint]
  (token/sha256-hex (.getBytes ^String endpoint "UTF-8")))

(defn devices
  "`user-id`'s devices with notifications on, the latest first:
  `{:id :created-at :key}` (see [[endpoint-key]])."
  [db user-id]
  (mapv (fn [{:keys [endpoint] :as row}] (-> row (dissoc :endpoint) (assoc :key (endpoint-key endpoint))))
        (jdbc/execute! db ["SELECT id, endpoint, created_at FROM push_subscription WHERE user_id = ? ORDER BY id DESC"
                           user-id]
                       db/opts)))

(defn queue!
  "Queues, in transaction `tx`, a notification of message `message-id` to
  each device of the other participants of thread `thread-id`, due `now`."
  [tx thread-id sender-id message-id now]
  (jdbc/execute-one! tx ["INSERT INTO push_delivery (subscription_id, message_id, due_at)
                          SELECT s.id, ?, ? FROM push_subscription s
                          JOIN thread_participant p ON p.user_id = s.user_id
                          WHERE p.thread_id = ? AND p.user_id <> ?"
                         message-id now thread-id sender-id]))
