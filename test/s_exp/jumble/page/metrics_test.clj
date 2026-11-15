(ns s-exp.jumble.page.metrics-test
  "GET /metrics, to the bearer of the token."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [s-exp.jumble.email.outbox :as outbox]
            [s-exp.jumble.moderation.model :as moderation]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]
            [s-exp.jumble.store.db :as db])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def ^:private token (apply str (repeat 32 "t")))

(defn- metrics
  [& {:keys [bearer settings]}]
  (fixtures/request :get "/metrics" nil
                    :headers (when bearer {"authorization" (str "Bearer " bearer)})
                    :ctx {:settings (merge {:metrics-token token} settings)}))

(defn- with-litestream
  "Calls `f` with the address of a server answering as Litestream's metrics
  do, its runtime's series among its own."
  [f]
  (let [body (.getBytes (str "# HELP go_goroutines Number of goroutines.\n"
                             "# TYPE go_goroutines gauge\n"
                             "go_goroutines 12\n"
                             "# HELP litestream_db_size The current size of the real DB\n"
                             "# TYPE litestream_db_size gauge\n"
                             "litestream_db_size{db=\"/data/jumble.db\"} 4096\n")
                        "UTF-8")
        server (doto (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
                 (.createContext "/metrics" (reify HttpHandler
                                              (handle [_ exchange]
                                                (let [^HttpExchange exchange exchange]
                                                  (.sendResponseHeaders exchange 200 (alength body))
                                                  (with-open [out (.getResponseBody exchange)]
                                                    (.write out body))))))
                 (.start))]
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/metrics"))
      (finally
        (.stop server 0)))))

(deftest litestream-test
  (testing "Litestream's own series follow, its runtime's left out"
    (with-litestream
      (fn [url]
        (let [body (:body (metrics :bearer token :settings {:litestream-metrics-url url}))]
          (is (str/includes? body "\nlitestream_db_size{db=\"/data/jumble.db\"} 4096\n"))
          (is (str/includes? body "# TYPE litestream_db_size gauge\n"))
          (is (not (str/includes? body "go_goroutines")))
          (is (str/includes? body "\njumble_litestream_up 1\n"))))))
  (testing "Litestream not answering: said so, the rest served"
    (let [{:keys [status body]} (metrics :bearer token :settings {:litestream-metrics-url "http://127.0.0.1:1/metrics"})]
      (is (= 200 status))
      (is (str/includes? body "\njumble_litestream_up 0\n"))
      (is (str/includes? body "\njumble_members "))))
  (testing "none configured (no Litestream, in development): nothing about it"
    (is (not (str/includes? (:body (metrics :bearer token)) "litestream")))))

(defn- value
  "The value of series `line-start` (its name and labels) in `text`, 0
  when absent."
  [text line-start]
  (or (some->> (str/split-lines text)
               (some #(when (str/starts-with? % (str line-start " ")) %))
               (re-find #" ([0-9.eE+-]+)$")
               second
               parse-double)
      0))

(deftest requests-test
  (let [before (:body (metrics :bearer token))
        series "jumble_http_requests_total{method=\"get\",route=\"/listings/{id}\",status=\"404\"}"
        unmatched "jumble_http_requests_total{method=\"get\",route=\"unmatched\",status=\"404\"}"]
    (fixtures/request :get "/listings/123456" (fixtures/sign-in! "alice@example.com"))
    (fixtures/request :get "/no/such/page" nil)
    (let [after (:body (metrics :bearer token))]
      (testing "counted by the route's pattern, not its path, method and status"
        (is (= 1.0 (- (value after series) (value before series)))))
      (testing "a path no route has, as unmatched"
        (is (= 1.0 (- (value after unmatched) (value before unmatched)))))
      (testing "timed, by method and route"
        (is (pos? (- (value after "jumble_http_request_duration_seconds_count{method=\"get\",route=\"/listings/{id}\"}")
                     (value before "jumble_http_request_duration_seconds_count{method=\"get\",route=\"/listings/{id}\"}"))))))))

(deftest jvm-test
  (testing "the JVM's memory, collections, threads and time"
    (let [body (:body (metrics :bearer token))]
      (doseq [series ["jvm_memory_used_bytes{area=\"heap\"}" "jvm_memory_committed_bytes{area=\"heap\"}"
                      "jvm_memory_used_bytes{area=\"nonheap\"}" "jvm_threads_current"
                      "process_cpu_seconds_total" "process_start_time_seconds"]]
        (is (pos? (value body series)) series))
      (is (re-find #"\njvm_gc_collections_total\{gc=\"[^\"]+\"\} \d+" body))
      (is (re-find #"\njvm_gc_collection_seconds_total\{gc=\"[^\"]+\"\} [0-9.]+" body)))))

(deftest metrics-test
  (let [ann (fixtures/user! "ann")
        gone (fixtures/user! "gone")]
    (fixtures/listing! ann "Lamp")
    (jdbc/execute! *db* ["UPDATE listing SET status = 'reserved' WHERE id = ?" (:id (fixtures/listing! ann "Chair"))])
    (jdbc/execute! *db* ["UPDATE user SET deleted_at = 1 WHERE id = ?" gone])
    (testing "counts, in Prometheus' text format, for the token's bearer"
      (let [{:keys [status headers body]} (metrics :bearer token)]
        (is (= 200 status))
        (is (str/starts-with? (get headers "content-type") "text/plain; version=0.0.4"))
        (is (str/includes? body "\njumble_members 1\n") "deleted accounts do not count")
        (is (str/includes? body "\njumble_listings{status=\"active\"} 1\n"))
        (is (str/includes? body "\njumble_listings{status=\"reserved\"} 1\n"))
        (is (str/includes? body "\njumble_listings{status=\"sold\"} 0\n"))))
    (testing "the outbox: emails waiting, being retried and given up on, and how
              long the oldest due has waited"
      (let [now (System/currentTimeMillis)]
        (db/with-write-tx [tx *db*]
          (outbox/enqueue! tx {:to "a@example.com" :subject "s" :text "t"} (- now 90000))
          (outbox/enqueue! tx {:to "b@example.com" :subject "s" :text "t"} (+ now 60000))
          (outbox/enqueue! tx {:to "c@example.com" :subject "s" :text "t"} now)
          (outbox/enqueue! tx {:to "d@example.com" :subject "s" :text "t"} now))
        (jdbc/execute! *db* ["UPDATE email_outbox SET attempts = 3 WHERE recipient = 'c@example.com'"])
        (jdbc/execute! *db* ["UPDATE email_outbox SET attempts = 10, given_up_at = ?, due_at = ? WHERE recipient = 'd@example.com'"
                             now (- now 999999)])
        (jdbc/execute! *db* ["INSERT INTO object_deletion (object_key, due_at) VALUES ('photos/1', 0), ('photos/2', 0)"])
        (let [body (:body (metrics :bearer token))]
          (is (str/includes? body "\njumble_outbox_emails{state=\"waiting\"} 2\n"))
          (is (str/includes? body "\njumble_outbox_emails{state=\"retrying\"} 1\n"))
          (is (str/includes? body "\njumble_outbox_emails{state=\"given_up\"} 1\n"))
          (is (re-find #"\njumble_outbox_oldest_due_seconds (9[0-9]|1[0-9][0-9])\n" body)
              "the email due 90 s ago, not the one given up on")
          (is (str/includes? body "\njumble_storage_deletions_pending 2\n")))))
    (testing "reports waiting for a moderator"
      (moderation/report! *db* {:listing-id (:id (fixtures/listing! ann "Odd")) :reporter-id (fixtures/user! "rep") :reason "?" :now 1 :alerts []})
      (is (str/includes? (:body (metrics :bearer token)) "\njumble_reports_open 1\n")))
    (testing "the database's size, and room left on its disk"
      (let [body (:body (metrics :bearer token))
            value #(some-> (re-find (re-pattern (str "\n" (java.util.regex.Pattern/quote %) " (\\d+)\n")) body) second parse-long)]
        (is (pos? (value "jumble_database_bytes{file=\"main\"}")))
        (is (some? (value "jumble_database_bytes{file=\"wal\"}")))
        (is (pos? (value "jumble_disk_bytes{state=\"total\"}")))
        (is (<= (value "jumble_disk_bytes{state=\"free\"}") (value "jumble_disk_bytes{state=\"total\"}")))))
    (testing "nobody else"
      (is (= 401 (:status (metrics))))
      (is (= 401 (:status (metrics :bearer "wrong"))))
      (is (= 401 (:status (metrics :bearer (str token "x"))))))
    (testing "off without a token: not found"
      (is (= 404 (:status (metrics :bearer token :settings {:metrics-token nil})))))))
