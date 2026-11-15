(ns s-exp.jumble.page.metrics
  "Counts about the community for monitoring and its host, in Prometheus'
  text format, to the bearer of the configured token: members, listings,
  the outbox and storage queued from the database, what the process counts
  and times (see [[metrics/text]]), and Litestream's own, read from it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.http :as http]
            [s-exp.jumble.email.outbox :as outbox]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.metrics :as metrics]
            [s-exp.jumble.moderation.model :as moderation]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.token :as token]
            [s-exp.jumble.view.layout :as layout]))

(set! *warn-on-reflection* true)

(def ^:private listing-statuses
  "Every status a listing can have (the listing table's check), each
  reported even at 0, so series do not come and go."
  ["active" "reserved" "sold" "given" "found" "expired"])

(defn- listing-counts
  [db]
  (merge (zipmap listing-statuses (repeat 0))
         (into {}
               (map (juxt :status :n))
               (jdbc/execute! db ["SELECT status, count(*) AS n FROM listing GROUP BY status"]
                              db/opts))))

(defn- sizes
  "In bytes: the database's file and its write-ahead log, and the free and
  total space of the disk holding them."
  [db]
  (let [path (some #(when (= "main" (:name %)) (:file %)) (jdbc/execute! db ["PRAGMA database_list"] db/opts))
        file (io/file path)
        wal (io/file (str path "-wal"))]
    {:main (.length file)
     :wal (if (.exists wal) (.length wal) 0)
     :free (.getUsableSpace file)
     :total (.getTotalSpace file)}))

(def ^:private litestream-client
  ;; Litestream answers from the same machine: anything slower is a fault.
  (delay (http/client {:connect-timeout 1000 :timeout 2000})))

(defn- litestream-series?
  "Whether `line` of Prometheus' text format is about one of Litestream's
  own metrics, not its runtime's."
  [line]
  (str/starts-with? (str/replace line #"^# (HELP|TYPE) " "") "litestream_"))

(defn- litestream
  "Litestream's metrics, read from `url`, then whether it answered; nothing
  without a `url`."
  [url]
  (when url
    (let [text (try
                 (let [{:keys [status body]} (http/request @litestream-client {:method :get :url url} :string)]
                   (when (= 200 status) body))
                 (catch Exception e
                   (log/warn e "Litestream's metrics could not be read")
                   nil))]
      (str (when text
             (str/join (map #(str % "\n") (filter litestream-series? (str/split-lines text)))))
           "# HELP jumble_litestream_up Whether Litestream answered for its metrics.\n"
           "# TYPE jumble_litestream_up gauge\n"
           "jumble_litestream_up " (if text 1 0) "\n"))))

(defn- exposition
  [db litestream-url]
  (let [{:keys [waiting retrying given-up oldest-due]} (outbox/counts db (System/currentTimeMillis))
        {:keys [main wal free total]} (sizes db)]
    (str (str/join "\n"
                   (concat
                    ["# HELP jumble_members Accounts not deleted."
                     "# TYPE jumble_members gauge"
                     (str "jumble_members " (account/member-count db))
                     "# HELP jumble_listings Listings, by status."
                     "# TYPE jumble_listings gauge"]
                    (for [[status n] (sort-by key (listing-counts db))]
                      (str "jumble_listings{status=\"" status "\"} " n))
                    ["# HELP jumble_outbox_emails Emails in the outbox: waiting (never tried), retrying (failed before), given_up."
                     "# TYPE jumble_outbox_emails gauge"
                     (str "jumble_outbox_emails{state=\"waiting\"} " waiting)
                     (str "jumble_outbox_emails{state=\"retrying\"} " retrying)
                     (str "jumble_outbox_emails{state=\"given_up\"} " given-up)
                     "# HELP jumble_outbox_oldest_due_seconds How long the email due first has waited, 0 when none is due."
                     "# TYPE jumble_outbox_oldest_due_seconds gauge"
                     (str "jumble_outbox_oldest_due_seconds " (quot oldest-due 1000))
                     "# HELP jumble_storage_deletions_pending Photo objects queued for deletion from storage."
                     "# TYPE jumble_storage_deletions_pending gauge"
                     (str "jumble_storage_deletions_pending " (listing/pending-deletions db))
                     "# HELP jumble_reports_open Reports waiting for a moderator."
                     "# TYPE jumble_reports_open gauge"
                     (str "jumble_reports_open " (moderation/open-count db))
                     "# HELP jumble_database_bytes Size of the database: its file (main) and write-ahead log (wal)."
                     "# TYPE jumble_database_bytes gauge"
                     (str "jumble_database_bytes{file=\"main\"} " main)
                     (str "jumble_database_bytes{file=\"wal\"} " wal)
                     "# HELP jumble_disk_bytes Space on the disk holding the database: free (usable) and total."
                     "# TYPE jumble_disk_bytes gauge"
                     (str "jumble_disk_bytes{state=\"free\"} " free)
                     (str "jumble_disk_bytes{state=\"total\"} " total)
                     ""]))
         (metrics/text)
         (metrics/jvm-text)
         (litestream litestream-url))))

(defn- bearer?
  "Whether `request` carries `token` as its bearer token."
  [request expected]
  (token/same? (get-in request [:headers "authorization"] "") (str "Bearer " expected)))

(defn metrics
  [{:keys [ctx] :as request}]
  (let [token (get-in ctx [:settings :metrics-token])]
    (cond
      (nil? token) (layout/not-found request)
      (not (bearer? request token)) {:status 401
                                     :headers {"content-type" "text/plain; charset=utf-8"
                                               "www-authenticate" "Bearer"}
                                     :body "Unauthorized"}
      :else {:status 200
             :headers {"content-type" "text/plain; version=0.0.4; charset=utf-8"}
             :body (exposition (:db ctx) (get-in ctx [:settings :litestream-metrics-url]))})))

(def routes
  {[:get "/metrics"] #'metrics})
