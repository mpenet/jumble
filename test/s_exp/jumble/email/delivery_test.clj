(ns s-exp.jumble.email.delivery-test
  "The delivery component, sending emails on its own, a failing step
  leaving the others."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.email.delivery :as delivery]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture listing! user!]]
            [s-exp.jumble.listing.freshness :as freshness]
            [s-exp.jumble.listing.weekly :as weekly]
            [s-exp.jumble.message.model :as message]
            [s-exp.jumble.metrics :as metrics]
            [s-exp.jumble.email.outbox :as outbox])
  (:import (java.util.concurrent ExecutorService)))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(defn- eventually
  "`(f)` once it is truthy, waiting up to 2 s."
  [f]
  (loop [tries 100]
    (let [v (f)]
      (if (or v (zero? tries))
        v
        (do (Thread/sleep 20) (recur (dec tries)))))))

(deftest component-test
  (testing "running, it sends message notifications and queued emails on its own"
    (let [alice (user! "alice")
          bob (user! "bob")
          emails (atom [])
          mailer #(swap! emails conj %)]
      (message/start! *db*
                      {:sender-id bob :listing-id (:id (listing! alice "Desk lamp")) :subject "Desk lamp"
                       :body "Still available?" :now 0})
      (db/with-write-tx [tx *db*]
        (outbox/enqueue! tx {:to "carol@example.com" :subject "Hello" :text "Body"} 0))
      (let [running (component/start (assoc (delivery/component {:mailer mailer :blobs (blob/memory-store) :settings fixtures/settings
                                                                 :interval-ms 20})
                                            :db {:datasource *db*}))]
        (try
          (is (= #{"alice@example.com" "carol@example.com"}
                 (eventually #(let [to (set (map :to @emails))] (when (= 2 (count to)) to)))))
          (finally
            (component/stop running)))))))

(deftest failing-step-test
  (testing "a step failing every round leaves the others running"
    (let [emails (atom [])
          mailer #(swap! emails conj %)]
      (db/with-write-tx [tx *db*]
        (outbox/enqueue! tx {:to "carol@example.com" :subject "Your sign-in link" :text "Body"} 0))
      (with-redefs [freshness/sweep! (fn [& _] (throw (ex-info "Database busy" {})))]
        (let [running (component/start (assoc (delivery/component {:mailer mailer :blobs (blob/memory-store) :settings fixtures/settings
                                                                   :interval-ms 20})
                                              :db {:datasource *db*}))]
          (try
            (is (= ["carol@example.com"] (eventually #(seq (map :to @emails)))))
            (testing "each step's runs counted, failed or not, and its last success marked"
              ;; Counted once the step returns, after the email went to the mailer.
              (let [text (eventually #(let [text (metrics/text)]
                                        (when (re-find #"jumble_background_last_success_seconds\{step=\"outbox\"\}" text)
                                          text)))]
                (is (re-find #"jumble_background_runs_total\{result=\"failed\",step=\"freshness\"\} [1-9]" text))
                (is (re-find #"jumble_background_runs_total\{result=\"ok\",step=\"outbox\"\} [1-9]" text))
                (is (re-find #"jumble_background_last_success_seconds\{step=\"outbox\"\} \d{10}" text))
                (is (re-find #"jumble_background_runs_total\{result=\"ok\",step=\"weekly digest\"\} [1-9]" text)
                    "the weekly email among the steps")))
            (finally
              (component/stop running))))))))

(deftest storage-step-test
  (testing "running, it deletes the photo objects due"
    (let [blobs (blob/memory-store)]
      (blob/put! blobs "photos/x/full.jpg" (byte-array 1) "image/jpeg")
      (blob/put! blobs "photos/x/thumb.jpg" (byte-array 1) "image/jpeg")
      (jdbc/execute! *db* ["INSERT INTO object_deletion (object_key, due_at) VALUES ('photos/x', 0)"])
      (let [running (component/start (assoc (delivery/component {:mailer (constantly nil) :blobs blobs
                                                                 :settings fixtures/settings :interval-ms 20})
                                            :db {:datasource *db*}))]
        (try
          (is (eventually #(empty? @(:objects blobs))))
          (finally
            (component/stop running)))))))

(deftest stopping-test
  (testing "stopped during a step, the round's later steps are not run"
    (let [in-step (promise)
          release (promise)
          later (atom 0)]
      (with-redefs [freshness/sweep! (fn [& _] (deliver in-step true) @release)
                    weekly/send-due! (fn [& _] (swap! later inc))]
        (let [running (component/start (assoc (delivery/component {:mailer (constantly nil) :blobs (blob/memory-store)
                                                                   :settings fixtures/settings :interval-ms 20})
                                              :db {:datasource *db*}))]
          (is (deref in-step 2000 false))
          (let [stopped (future (component/stop running))]
            (is (eventually #(.isShutdown ^ExecutorService (:executor running))))
            (deliver release true)
            @stopped)
          (is (zero? @later)))))))
