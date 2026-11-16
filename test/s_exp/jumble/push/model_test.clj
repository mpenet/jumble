(ns s-exp.jumble.push.model-test
  "Browser notifications: the devices members turn them on for, while signed
  in there, and a notification to each of the recipient's devices per new
  message."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [s-exp.jumble.account.data :as data]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]
            [s-exp.jumble.message.model :as message]
            [s-exp.jumble.push.crypto :as crypto]
            [s-exp.jumble.push.delivery :as delivery]
            [s-exp.jumble.push.model :as push]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.store.db :as db])
  (:import (java.security SecureRandom)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(defn- b64 ^String [^bytes bs] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- device
  "A browser's subscription to `endpoint`."
  [endpoint]
  {:endpoint endpoint
   :p256dh (b64 (crypto/public-bytes (:public (crypto/generate-keys))))
   :auth (b64 (let [bs (byte-array 16)] (.nextBytes (SecureRandom.) bs) bs))})

(def ^:private fcm "https://fcm.googleapis.com/fcm/send/")

(defn- session!
  "A session of `user-id`'s: its token."
  [user-id]
  (account/create-session! *db* user-id 0 fixtures/session-ttl))

(defn- subscribe!
  "`user-id` turns notifications on for `endpoint`, signed in with session
  `token` (a new one when not given)."
  ([user-id endpoint] (subscribe! user-id (session! user-id) endpoint))
  ([user-id token endpoint] (push/subscribe! *db* user-id token (device endpoint) 1)))

(defn- deliveries []
  (jdbc/execute! *db* ["SELECT * FROM push_delivery ORDER BY id"] db/opts))

(deftest keys-test
  (testing "the community's keys are made once and kept"
    (is (= (crypto/application-key (push/keys! *db*)) (crypto/application-key (push/keys! *db*))))))

(deftest subscribe-test
  (let [ann (fixtures/user! "ann")
        bob (fixtures/user! "bob")
        token (session! ann)]
    (testing "a device of a push service is added"
      (is (subscribe! ann token (str fcm "a")))
      (is (subscribe! ann token "https://updates.push.services.mozilla.com/wpush/v2/b"))
      (is (subscribe! ann token "https://web.push.apple.com/c"))
      (is (= 3 (count (push/devices *db* ann)))))
    (testing "nothing else, so Jumble never posts anywhere else"
      (doseq [endpoint ["http://fcm.googleapis.com/fcm/send/x" "https://169.254.169.254/latest" "https://127.0.0.1:9090/"
                        "https://fcm.googleapis.com.example.org/x" "https://evilfcm.googleapis.com/x" "not a url"
                        "https://fcm.googleapis.com:8443/x" "https://user@fcm.googleapis.com/x"]]
        (is (not (subscribe! ann token endpoint)) endpoint)))
    (testing "nor keys that are not keys a browser makes"
      (let [d (device (str fcm "d"))
            off-curve (b64 (byte-array (cons 4 (repeat 64 1))))]
        (is (not (push/subscribe! *db* ann token (assoc d :p256dh "AAAA") 1)))
        (is (not (push/subscribe! *db* ann token (assoc d :p256dh off-curve) 1)))
        (is (not (push/subscribe! *db* ann token (assoc d :auth "") 1)))))
    (testing "a device subscribing again, for whoever signed in on it"
      (subscribe! bob (str fcm "a"))
      (is (= 2 (count (push/devices *db* ann))))
      (is (= 1 (count (push/devices *db* bob)))))
    (testing "each device known by a digest of its address, which pages may show"
      (is (= (push/endpoint-key (str fcm "a")) (:key (first (push/devices *db* bob)))))
      (is (not (str/includes? (str (push/devices *db* bob)) "fcm"))))
    (testing "removed by its owner only"
      (let [{:keys [id]} (first (push/devices *db* bob))]
        (push/remove! *db* ann id)
        (is (= 1 (count (push/devices *db* bob))))
        (push/remove! *db* bob id)
        (is (empty? (push/devices *db* bob)))))
    (testing "or turned off on the device itself, by its address"
      (push/unsubscribe! *db* ann "https://web.push.apple.com/c")
      (is (= 1 (count (push/devices *db* ann)))))))

(deftest at-most-test
  (let [ann (fixtures/user! "ann")
        token (session! ann)]
    (testing "ten devices a member at most, the oldest making way"
      (doseq [i (range 12)]
        (subscribe! ann token (str fcm i)))
      (is (= 10 (count (push/devices *db* ann))))
      (is (not-any? #{(push/endpoint-key (str fcm 0)) (push/endpoint-key (str fcm 1))}
                    (map :key (push/devices *db* ann)))))))

(deftest signed-out-test
  (let [ann (fixtures/user! "ann")
        phone (session! ann)
        laptop (session! ann)]
    (subscribe! ann phone (str fcm "phone"))
    (subscribe! ann laptop (str fcm "laptop"))
    (testing "signing out on a device stops its notifications, not the others'"
      (account/delete-session! *db* phone)
      (is (= [(push/endpoint-key (str fcm "laptop"))] (map :key (push/devices *db* ann)))))))

(defn- thread!
  "Bob writes to Ann about her lamp; returns the thread."
  [ann bob now]
  (message/start! *db*
                  {:sender-id bob :listing-id (:id (fixtures/listing! ann "Desk lamp")) :subject "Desk lamp"
                   :body "Still available?" :now now}))

(deftest queue-test
  (let [ann (fixtures/user! "ann")
        bob (fixtures/user! "bob")]
    (subscribe! ann (str fcm "phone"))
    (subscribe! ann (str fcm "laptop"))
    (subscribe! bob (str fcm "bob"))
    (testing "a new message, one notification to each of the recipient's devices"
      (let [thread (thread! ann bob 10)]
        (is (= 2 (count (deliveries))))
        (testing "not to the sender's"
          (message/reply! *db* thread ann "Yes" 20)
          (is (= 3 (count (deliveries)))))))
    (testing "a device signed in to by someone else no longer gets the earlier one's"
      (subscribe! bob (str fcm "phone"))
      (is (= 2 (count (deliveries)))))))

(defn- sender
  "A push service answering `status`, recording the requests."
  [requests status]
  (fn [request] (swap! requests conj request) {:status status}))

(defn- deliver! [send now] (delivery/deliver-due! *db* send fixtures/settings now (constantly true)))

(deftest delivery-test
  (let [ann (fixtures/user! "ann")
        bob (fixtures/user! "bob")
        requests (atom [])]
    (subscribe! ann (str fcm "phone"))
    (thread! ann bob 10)
    (testing "sent encrypted and signed to the device's push service, then done"
      (is (= 1 (deliver! (sender requests 201) 10)))
      (let [{:keys [url headers ^bytes body]} (first @requests)]
        (is (= (str fcm "phone") url))
        (is (= "aes128gcm" (get headers "content-encoding")))
        (is (str/starts-with? (get headers "authorization") "vapid t="))
        (is (some? (get headers "ttl")))
        (is (< 100 (alength body))))
      (is (empty? (deliveries))))
    (testing "a device its push service no longer knows is forgotten"
      (doseq [status [410 404]]
        (subscribe! ann (str fcm "phone"))
        (thread! ann bob 20)
        (deliver! (sender requests status) 20)
        (is (empty? (push/devices *db* ann)) status)
        (is (empty? (deliveries)) status)))
    (testing "one refused, as for a clock or setup at fault, is tried again, the device kept"
      (subscribe! ann (str fcm "phone"))
      (thread! ann bob 30)
      (deliver! (sender requests 403) 30)
      (is (= 1 (count (push/devices *db* ann))))
      (is (= [1] (map :attempts (deliveries)))))))

(deftest retry-test
  (let [ann (fixtures/user! "ann")
        bob (fixtures/user! "bob")
        requests (atom [])
        minute (* 60 1000)]
    (subscribe! ann (str fcm "phone"))
    (thread! ann bob 10)
    (testing "a push service failing, tried again later"
      (is (zero? (deliver! (sender requests 503) 10)))
      (is (zero? (deliver! (sender requests 201) (+ 10 (* 30 1000)))) "not at once")
      (is (= 1 (deliver! (sender requests 201) (+ 10 (* 2 minute))))))
    (testing "unreachable, or failing any other way, likewise: nothing stays due for ever"
      (thread! ann bob (* 10 minute))
      (is (zero? (deliver! (fn [_] (throw (java.io.IOException. "down"))) (* 10 minute))))
      (is (zero? (deliver! (fn [_] (throw (IllegalStateException. "bug"))) (* 20 minute))))
      (is (= [2] (map :attempts (deliveries))))
      (doseq [i (range 3)]
        (deliver! (fn [_] (throw (IllegalStateException. "bug"))) (* (+ 30 (* 30 i)) minute)))
      (is (empty? (deliveries)) "given up on"))))

(deftest needless-test
  (let [ann (fixtures/user! "ann")
        bob (fixtures/user! "bob")
        requests (atom [])]
    (subscribe! ann (str fcm "phone"))
    (let [thread (thread! ann bob 10)]
      (testing "a message read before its notification went out is not notified"
        (message/mark-read! *db* thread ann (:id (jdbc/execute-one! *db* ["SELECT max(id) AS id FROM message"] db/opts)))
        (is (zero? (deliver! (sender requests 201) 10)))
        (is (empty? @requests))
        (is (empty? (deliveries)))))
    (testing "nor one from someone whose account was deleted since"
      (thread! ann bob 20)
      (data/delete-account! *db* (blob/memory-store) bob 30)
      (is (zero? (deliver! (sender requests 201) 40)))
      (is (empty? @requests))
      (is (empty? (deliveries))))))

(deftest signed-in-only-test
  (let [ann (fixtures/user! "ann")
        bob (fixtures/user! "bob")
        requests (atom [])]
    (subscribe! ann (str fcm "phone"))
    (testing "not to a device whose sign-in expired, though not yet cleared away"
      (thread! ann bob 10)
      (jdbc/execute! *db* ["UPDATE session SET expires_at = 5 WHERE user_id = ?" ann])
      (is (zero? (deliver! (sender requests 201) 20)))
      (is (empty? @requests))
      (is (empty? (deliveries))))
    (testing "nor to someone no longer let in"
      (jdbc/execute! *db* ["UPDATE session SET expires_at = ? WHERE user_id = ?" Long/MAX_VALUE ann])
      (thread! ann bob 30)
      (is (zero? (delivery/deliver-due! *db*
                                        (sender requests 201)
                                        (assoc fixtures/settings :allowed-emails ["bob@example.com"])
                                        40
                                        (constantly true))))
      (is (empty? @requests))
      (is (empty? (deliveries))))))
