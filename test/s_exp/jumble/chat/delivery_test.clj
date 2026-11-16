(ns s-exp.jumble.chat.delivery-test
  "New posts announced to the community's chat channel through its webhook,
  tried again while it fails; those gone, closed or seeded long ago are not."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [s-exp.jumble.account.data :as data]
            [s-exp.jumble.chat.delivery :as delivery]
            [s-exp.jumble.chat.model :as chat]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.oda :as oda]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def ^:private webhook "https://hooks.slack.com/services/T0/B0/secret")

(def ^:private settings (assoc fixtures/settings :chat-webhook-url webhook))

(def ^:private minute (* 60 1000))

(defn- post!
  "Ann's free listing `title`, posted at `now`."
  [ann title now]
  (listing/create! *db* (blob/memory-store) ann
                   (:listing (listing/validate fixtures/settings {:kind "free" :title title :category "home"
                                                                  :pickup "place:hall"}))
                   [] now))

(defn- channel
  "A webhook answering `status`, recording the requests."
  [requests status]
  (fn [request] (swap! requests conj request) {:status status}))

(defn- webhook-queue []
  (filter #(= "webhook" (:channel %)) (chat/queued *db*)))

(defn- deliver!
  ([send now] (deliver! send settings now))
  ([send settings now] (delivery/deliver-webhook! *db* send settings now (constantly true))))

(defn- said
  "The text of each request to the channel."
  [requests]
  (map #(get (oda/parse (:body %)) "text") @requests))

(deftest announce-test
  (let [ann (fixtures/user! "ann")
        requests (atom [])
        lamp (post! ann "Desk lamp" 0)]
    (testing "nothing before it is due"
      (is (zero? (deliver! (channel requests 200) (dec chat/delay-ms))))
      (is (empty? @requests)))
    (testing "then posted to the webhook as JSON, once"
      (is (= 1 (deliver! (channel requests 200) chat/delay-ms)))
      (let [{:keys [url method headers]} (first @requests)]
        (is (= [webhook :post "application/json"] [url method (get headers "content-type")])))
      (is (str/includes? (first (said requests)) "Desk lamp"))
      (is (str/includes? (first (said requests)) (str "/listings/" lamp)))
      (is (empty? (webhook-queue))))))

(deftest retry-test
  (let [ann (fixtures/user! "ann")
        requests (atom [])]
    (post! ann "Desk lamp" 0)
    (testing "the channel failing or out of reach, tried again later"
      (is (zero? (deliver! (channel requests 503) chat/delay-ms)))
      (is (zero? (deliver! (fn [_] (throw (java.io.IOException. "down"))) (* 2 chat/delay-ms))))
      (is (= [2] (map :attempts (webhook-queue))))
      (is (= 1 (deliver! (channel requests 200) (* 60 minute)))))
    (testing "and given up on in the end"
      (post! ann "Chair" (* 60 minute))
      (doseq [i (range 10)]
        (deliver! (channel requests 429) (* (+ 2 i) 60 minute)))
      (is (empty? (webhook-queue))))))

(deftest refused-test
  (let [ann (fixtures/user! "ann")
        requests (atom [])]
    (post! ann "Desk lamp" 0)
    (testing "a webhook deleted, or refusing it, is not asked again"
      (is (zero? (deliver! (channel requests 404) chat/delay-ms)))
      (is (empty? (webhook-queue))))))

(deftest needless-test
  (let [ann (fixtures/user! "ann")
        bob (fixtures/user! "bob")
        requests (atom [])
        send (channel requests 200)]
    (testing "not a post closed meanwhile"
      (let [lamp (post! ann "Desk lamp" 0)]
        (listing/set-status! *db* lamp ann "given" minute)
        (deliver! send chat/delay-ms)))
    (testing "nor one whose owner deleted their account"
      (post! bob "Chair" 0)
      (data/delete-account! *db* (blob/memory-store) bob minute)
      (deliver! send chat/delay-ms))
    (testing "nor one posted long before, as a seed does, or left from an outage"
      (post! ann "Old sofa" 0)
      (deliver! send (* 3 24 60 minute)))
    (is (empty? @requests))
    (is (empty? (webhook-queue)))))

(deftest no-channel-test
  (let [ann (fixtures/user! "ann")
        requests (atom [])]
    (post! ann "Desk lamp" 0)
    (testing "without a webhook, nothing is sent and the queue does not grow"
      (is (zero? (deliver! (channel requests 200) fixtures/settings chat/delay-ms)))
      (is (empty? @requests))
      (is (empty? (webhook-queue))))))

(defn- irc-queue []
  (filter #(= "irc" (:channel %)) (chat/queued *db*)))

(defn- deliver-irc!
  ([say now] (deliver-irc! say settings now))
  ([say settings now] (delivery/deliver-irc! *db* say settings now (constantly true))))

(deftest irc-test
  (let [ann (fixtures/user! "ann")
        said (atom [])
        lamp (post! ann "Desk lamp" 0)]
    (testing "said by the bot, on one line with the link"
      (is (= 1 (deliver-irc! #(do (swap! said conj %) true) (assoc settings :irc {:channel "#swap"}) chat/delay-ms)))
      (is (= [(str "New on Jumble: Desk lamp · Free · Furniture & home · Main hall · http://localhost:8080/listings/" lamp)] @said))
      (is (empty? (irc-queue))))
    (testing "the bot out of its channel, it waits for it, without counting tries"
      (post! ann "Chair" 0)
      (is (zero? (deliver-irc! (constantly false) (assoc settings :irc {:channel "#swap"}) chat/delay-ms)))
      (is (= [0] (map :attempts (irc-queue)))))
    (testing "without a bot, the queue is emptied"
      (is (zero? (deliver-irc! nil settings chat/delay-ms)))
      (is (empty? (irc-queue))))))
