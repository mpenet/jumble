(ns s-exp.jumble.listing.freshness
  "Keeps the board to things still on offer. Owners forget to mark what is
  gone: a listing on the board without news for the community's
  `:listing-reminder-days` (60 by default, 0 never) gets its owner asked, by
  email, whether it is still available; unanswered for 15 more days, it
  expires, off the board until its owner puts it back. Posting, editing,
  changing the status and answering are all news. A listing to lend is a
  standing offer: never asked about, never expired."
  (:require [next.jdbc :as jdbc]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.metrics :as metrics]
            [s-exp.jumble.email.mail :as mail]
            [s-exp.jumble.email.outbox :as outbox]))

(set! *warn-on-reflection* true)

(def ^:private day (* 24 60 60 1000))

(def expire-after-days 15)

(def ^:private max-give-ups
  "Reminders the mail server refuses in a row before the owner counts as not
  answering: once is an outage, every time an address that no longer works."
  3)

(def ^:private max-reminders-per-day
  "Owners asked a day at most: a backlog (the first start of an instance
  with old listings, say) is spread over days, within mail providers'
  limits."
  100)

(def ^:private unsent-reminder
  "A reminder about listing l still in the outbox, or given up on there."
  "EXISTS (SELECT 1 FROM email_outbox o WHERE o.listing_id = l.id)")

(defn reminder
  "Email asking the owner of listing `id` whether it is still available, or
  for a wanted post whether they are still looking."
  [{:keys [base-url app-name listing-reminder-days]} {:keys [id kind title email]}]
  (let [title (mail/one-line title)
        wanted (= "wanted" kind)]
    {:to email
     :listing-id id
     :subject (if wanted
                (i18n/t "Still looking for \"{title}\"?" {:title title})
                (i18n/t "Is \"{title}\" still available?" {:title title}))
     :text (str (i18n/tn "You posted \"{title}\" on {app-name} {n} day ago or more."
                         "You posted \"{title}\" on {app-name} {n} days ago or more."
                         listing-reminder-days
                         {:title title :app-name app-name})
                "\n\n"
                (if wanted
                  (i18n/t "Still looking for it? Say so on its page, or mark it as found:")
                  (i18n/t "Is it still available? Say so on its page, or mark it as gone:"))
                "\n"
                base-url
                "/listings/"
                id
                "\n\n"
                (i18n/tn "Without an answer within {n} day, it leaves the board. You can put it back from My listings at any time."
                         "Without an answer within {n} days, it leaves the board. You can put it back from My listings at any time."
                         expire-after-days)
                "\n")}))

(def ^:private asked-about
  "Listings whose owner may be asked whether they are still available: on
  the board, and not to lend."
  (str "status IN " listing/on-board-sql " AND kind <> 'lend'"))

(defn- due-reminders
  "Listings whose owner is due a reminder after `days` without news, the
  longest without news first, within today's allowance."
  [db now days]
  (let [asked-today (:n (jdbc/execute-one! db
                                           ["SELECT count(*) AS n FROM listing WHERE reminded_at > ?" (- now day)]
                                           db/opts))
        allowance (max 0 (- max-reminders-per-day asked-today))]
    (when (pos? allowance)
      (jdbc/execute! db
                     [(str "SELECT l.id, l.kind, l.title, u.email, u.language FROM listing l JOIN user u ON u.id = l.user_id
                               WHERE l."
                           asked-about
                           " AND u.deleted_at IS NULL
                                 AND l.reminded_at IS NULL AND l.fresh_at <= ?
                               ORDER BY l.fresh_at LIMIT ?")
                      (- now (* days day))
                      allowance]
                     db/opts))))

(defn- given-up-reminders?
  [db]
  (some? (jdbc/execute-one! db
                            ["SELECT 1 FROM email_outbox
                                 WHERE listing_id IS NOT NULL AND given_up_at IS NOT NULL LIMIT 1"])))

(defn- expiring?
  [db now]
  (some? (jdbc/execute-one! db
                            [(str "SELECT 1 FROM listing l WHERE "
                                  asked-about
                                  "
                                      AND reminded_at <= ? AND NOT "
                                  unsent-reminder
                                  " LIMIT 1")
                             (- now (* expire-after-days day))])))

(defn sweep!
  "Asks the owners of listings due a reminder, through the outbox, and
  expires those asked long enough ago without an answer, once the reminder
  went out: one the mail server never took is sent again instead, until it
  refused [[max-give-ups]] in a row, which count as not answering. An owner
  no longer allowed in is not asked, so theirs expire as unanswered. With
  `:listing-reminder-days` 0 in `settings`, nobody is asked and nothing
  expires. Writes only when there is something to do: it runs every
  delivery round."
  [db {:keys [listing-reminder-days] :as settings} now]
  (when (given-up-reminders? db)
    (db/with-write-tx [tx db]
      ;; Asked again by clearing reminded_at; kept, the reminder counts as sent.
      (jdbc/execute-one! tx
                         ["UPDATE listing SET reminder_give_ups = reminder_give_ups + 1,
                                     reminded_at = CASE WHEN reminder_give_ups + 1 < ? THEN NULL ELSE reminded_at END
                              WHERE id IN (SELECT listing_id FROM email_outbox WHERE given_up_at IS NOT NULL)"
                          max-give-ups])
      (jdbc/execute-one! tx ["DELETE FROM email_outbox WHERE listing_id IS NOT NULL AND given_up_at IS NOT NULL"])))
  (let [due (when (pos? listing-reminder-days) (due-reminders db now listing-reminder-days))]
    (when (and (pos? listing-reminder-days) (or (seq due) (expiring? db now)))
      (db/with-write-tx [tx db]
        (let [allowed (account/allowed tx (:allowed-emails settings))]
          (doseq [{:keys [id email] :as found} due
                  ;; Unless its owner answered in the meantime.
                  :when (jdbc/execute-one! tx
                                           [(str "UPDATE listing SET reminded_at = ?
                                                     WHERE id = ? AND reminded_at IS NULL AND fresh_at <= ?
                                                       AND "
                                                 asked-about
                                                 " RETURNING id")
                                            now
                                            id
                                            (- now (* listing-reminder-days day))])]
            (when (allowed email)
              (outbox/enqueue! tx (i18n/speaking (:language found) (reminder settings found)) now))))
        (let [expired (::jdbc/update-count (jdbc/execute-one! tx
                                                              [(str "UPDATE listing AS l SET status = 'expired', updated_at = ?
                                                    WHERE "
                                                                    asked-about
                                                                    " AND reminded_at <= ?
                                                      AND NOT "
                                                                    unsent-reminder)
                                                               now
                                                               (- now (* expire-after-days day))]))]
          (when (pos? expired)
            (metrics/inc! listing/closed {:outcome "expired"} expired)))))))
