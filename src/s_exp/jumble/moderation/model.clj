(ns s-exp.jumble.moderation.model
  "Reports of listings by members, handled by moderators: a listing is removed
  or its reports dismissed. Reports are kept, resolved, as a record."
  (:require [next.jdbc :as jdbc]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.email.outbox :as outbox]
            [s-exp.jumble.web.params :as params]))

(set! *warn-on-reflection* true)

(defn validate-reason
  "An optional reason given with a report. Returns `{:reason normalized}` or
  `{:errors {:reason message}}`."
  [reason]
  (let [reason (or (params/trimmed reason) "")]
    (if (> (count reason) 1000)
      {:errors {:reason (i18n/t "Keep it to 1000 characters or fewer.")}}
      {:reason reason})))

(defn report!
  "Reports listing `listing-id` by `reporter-id`. On the listing's first open
  report the `alerts` emails are queued, for the moderators. Returns `:new`,
  `:already` when that person has an open report on it, or nil when the
  listing is gone or theirs."
  [db {:keys [listing-id reporter-id reason now alerts]}]
  (db/with-write-tx [tx db]
    (let [{:keys [title user-id]} (jdbc/execute-one! tx
                                                     ["SELECT title, user_id FROM listing WHERE id = ?" listing-id]
                                                     db/opts)
          open (when title
                 (jdbc/execute! tx
                                ["SELECT reporter_id FROM report WHERE listing_id = ? AND resolution IS NULL"
                                 listing-id]
                                db/opts))]
      (cond
        (or (nil? title) (= reporter-id user-id)) nil
        (some #(= reporter-id (:reporter-id %)) open) :already

        :else
        (do (jdbc/execute-one! tx
                               ["INSERT INTO report (listing_id, listing_title, reporter_id, reason, created_at)
                                          VALUES (?, ?, ?, ?, ?)"
                                listing-id
                                title
                                reporter-id
                                reason
                                now])
            (when (empty? open)
              (doseq [alert alerts]
                (outbox/enqueue! tx alert now)))
            :new)))))

(defn open-reports
  "Listings with open reports, the longest waiting first, each with its
  owner and reports."
  [db]
  (->> (jdbc/execute! db
                      ["SELECT r.listing_id, r.listing_title, o.id AS owner_id, o.name AS owner_name, o.email AS owner_email,
                                  u.name AS reporter_name, r.reason, r.created_at
                           FROM report r
                           JOIN listing l ON l.id = r.listing_id
                           JOIN user o ON o.id = l.user_id
                           JOIN user u ON u.id = r.reporter_id
                           WHERE r.resolution IS NULL
                           ORDER BY min(r.created_at) OVER (PARTITION BY r.listing_id), r.listing_id,
                                    r.created_at, r.id"]
                      db/opts)
       (partition-by :listing-id)
       (mapv (fn [[row :as rows]]
               (assoc (select-keys row [:listing-id :listing-title :owner-id :owner-name :owner-email])
                      :reports (mapv #(select-keys % [:reporter-name :reason :created-at]) rows))))))

(defn open-count
  "How many listings have open reports."
  [db]
  (:n (jdbc/execute-one! db ["SELECT count(DISTINCT listing_id) AS n FROM report WHERE resolution IS NULL"] db/opts)))

(defn- resolve!
  [tx listing-id resolution moderator-id now]
  (:next.jdbc/update-count (jdbc/execute-one! tx
                                              ["UPDATE report SET resolution = ?, resolved_by = ?, resolved_at = ?
                           WHERE listing_id = ? AND resolution IS NULL"
                                               resolution
                                               moderator-id
                                               now
                                               listing-id])))

(defn remove-listing!
  "Removes listing `listing-id` as moderator `moderator-id`, resolving its open
  reports and queueing `notice` to its owner. Removing a listing nobody
  reported records it as a report by the moderator, with `reason`, resolved
  at once. True when it was there."
  [db blobs {:keys [listing-id moderator-id now notice reason]}]
  (listing/remove! db
                   blobs
                   listing-id
                   (fn [tx {:keys [title]}]
                     (when (zero? (resolve! tx listing-id "removed" moderator-id now))
                       (jdbc/execute-one! tx
                                          ["INSERT INTO report (listing_id, listing_title, reporter_id, reason, created_at,
                                                                   resolution, resolved_by, resolved_at)
                                               VALUES (?, ?, ?, ?, ?, 'removed', ?, ?)"
                                           listing-id
                                           title
                                           moderator-id
                                           (or reason "")
                                           now
                                           moderator-id
                                           now]))
                     (outbox/enqueue! tx notice now))))

(defn dismiss!
  "Dismisses the open reports of listing `listing-id` as moderator `moderator-id`,
  keeping the listing. Returns how many there were."
  [db {:keys [listing-id moderator-id now]}]
  (db/with-write-tx [tx db]
    (resolve! tx listing-id "dismissed" moderator-id now)))
