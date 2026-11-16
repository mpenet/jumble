(ns s-exp.jumble.email.outbox
  "Emails written in the transaction of what they are about, then sent in
  the background, retried while the mail server fails."
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [s-exp.ex :as ex]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.error :as error]
            [s-exp.jumble.email.mail :as mail]
            [s-exp.jumble.metrics :as metrics]))

(set! *warn-on-reflection* true)

(def ^:private minute (* 60 1000))

(def ^:private batch-size 100)

(def emails
  (metrics/counter "jumble_emails_total"
                   "Emails handed to the mail server, by result: sent or failed."
                   [{:result "sent"} {:result "failed"}]))

(def max-attempts
  "Failed sends before an email is given up on."
  10)

(def ^:private given-up-kept
  "How long an email given up on is kept, marked, for what it was about to
  be followed up (see freshness/sweep!)."
  (* 7 24 60 minute))

(defn retry-at
  "When to try again after the `attempts`th failure at `now`: a delay
  doubling from a minute up to an hour."
  [attempts now]
  (+ now (min (* 60 minute) (* minute (bit-shift-left 1 (dec attempts))))))

(def ^:private record-attempts
  "Tries at recording a sent email while the database is busy, each
  waiting for the write lock."
  12)

(defn record-sent!
  "Calls `f` to record that an email went out, again while the database is
  busy: unrecorded, it would be sent again."
  [f]
  (loop [attempt 1]
    (let [outcome (try
                    (f)
                    ::recorded
                    (catch clojure.lang.ExceptionInfo e
                      (if (and (= ::error/write-lock-timeout (ex/ex-type e)) (< attempt record-attempts))
                        ::busy
                        (throw e))))]
      (when (= ::busy outcome)
        (recur (inc attempt))))))

(defn deliver-each
  "Calls `(deliver! item)` for each of `items` while `running` returns
  true, skipping those that throw, logged with `(describe item)` (ids, no
  personal data). Returns how many returned true, i.e. emails that went out."
  [items running deliver! describe]
  (reduce (fn [sent item]
            (if-not (running)
              (reduced sent)
              (if (try
                    (deliver! item)
                    (catch Exception e
                      (log/error e "Email delivery failed" (describe item))
                      false))
                (inc sent)
                sent)))
          0
          items))

(defn enqueue-each!
  "Calls `(enqueue! item)` for each of `items`, one that throws leaving the
  others: each failure is logged with `(describe item)` (ids, no personal
  data), then, once all were tried, an exception says how many failed, for
  the work to count as failed."
  [items enqueue! describe]
  (let [failed (count (remove (fn [item]
                                (try
                                  (enqueue! item)
                                  true
                                  (catch Exception e
                                    (log/error e "Queueing emails failed" (describe item))
                                    false)))
                              items))]
    (when (pos? failed)
      (throw (ex-info (str "Queueing emails failed for " failed " of " (count items)) {:failed failed})))))

(defn enqueue!
  "Queues `email` (`{:to :subject :text}`, `:listing-id` when it is about
  one, and `:expires-at` when it is of use until then only: it is then sent
  before the others, or dropped) in transaction `tx`, due `now`."
  [tx {:keys [to subject text listing-id expires-at]} now]
  (jdbc/execute-one! tx
                     ["INSERT INTO email_outbox (recipient, subject, body, due_at, listing_id, expires_at)
                          VALUES (?, ?, ?, ?, ?, ?)"
                      to
                      subject
                      text
                      now
                      listing-id
                      expires-at]))

(defn- failed!
  [db {:keys [id attempts]} now]
  (let [attempts (inc attempts)]
    (db/with-write-tx [tx db]
      (if (< attempts max-attempts)
        (do (jdbc/execute-one! tx
                               ["UPDATE email_outbox SET attempts = ?, due_at = ? WHERE id = ?"
                                attempts
                                (retry-at attempts now)
                                id])
            :retry)
        (do (jdbc/execute-one! tx
                               ["UPDATE email_outbox SET attempts = ?, given_up_at = ? WHERE id = ?"
                                attempts
                                now
                                id])
            :give-up)))))

(defn- purge-given-up!
  "Deletes the emails given up on long enough ago, if any."
  [db now]
  (let [before (- now given-up-kept)]
    (when (jdbc/execute-one! db ["SELECT 1 FROM email_outbox WHERE given_up_at <= ? LIMIT 1" before])
      (db/with-write-tx [tx db]
        (jdbc/execute-one! tx ["DELETE FROM email_outbox WHERE given_up_at <= ?" before])))))

(defn- deliver!
  "Sends one queued email; true when it went out."
  [db mailer {:keys [id recipient subject body] :as row} now]
  (if-let [error (try
                   (mailer {:to recipient :subject subject :text body})
                   nil
                   (catch Exception e
                     e))]
    (let [outcome (failed! db row now)]
      (metrics/inc! emails {:result "failed"})
      (log/warn "Email failed" (merge {:id id :outcome outcome} (mail/failure error)))
      false)
    (do (metrics/inc! emails {:result "sent"})
        (record-sent! #(db/with-write-tx [tx db]
                         (jdbc/execute-one! tx ["DELETE FROM email_outbox WHERE id = ?" id])))
        true)))

(defn counts
  "The emails waiting to be sent (`:waiting`, never tried; `:retrying`,
  tried and failed), those given up on, and how long the one due first has
  waited at `now`, in milliseconds, 0 when none is due."
  [db now]
  (jdbc/execute-one! db
                     ["SELECT
                            count(*) FILTER (WHERE given_up_at IS NULL AND attempts = 0) AS waiting,
                            count(*) FILTER (WHERE given_up_at IS NULL AND attempts > 0) AS retrying,
                            count(*) FILTER (WHERE given_up_at IS NOT NULL) AS given_up,
                            coalesce(? - min(due_at) FILTER (WHERE given_up_at IS NULL AND due_at <= ?), 0) AS oldest_due
                          FROM email_outbox"
                      now
                      now]
                     db/opts))

(defn deliver-due!
  "Sends a batch of the emails due at `now`, those that expire first, one by
  one while `running` returns true; one failing leaves the others
  unaffected. Expired ones are dropped. Returns how many went out."
  [db mailer now running]
  (purge-given-up! db now)
  (when (jdbc/execute-one! db ["SELECT 1 FROM email_outbox WHERE expires_at <= ? LIMIT 1" now])
    (db/with-write-tx [tx db]
      (jdbc/execute-one! tx ["DELETE FROM email_outbox WHERE expires_at <= ?" now])))
  (deliver-each (jdbc/execute! db
                               ["SELECT id, recipient, subject, body, attempts FROM email_outbox
                                    WHERE due_at <= ? AND given_up_at IS NULL
                                      -- Waiting for the deletion of their account to drop them.
                                      AND NOT EXISTS (SELECT 1 FROM user u WHERE u.email = lower(recipient) AND u.deleted_at IS NOT NULL)
                                    ORDER BY expires_at IS NULL, due_at, id LIMIT ?"
                                now
                                batch-size]
                               db/opts)
                running
                #(deliver! db mailer % now)
                #(select-keys % [:id])))
