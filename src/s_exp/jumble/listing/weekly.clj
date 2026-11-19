(ns s-exp.jumble.listing.weekly
  "The weekly email of what is new on the board, to each member who has not
  turned it off: a week after they joined, then a week after the last;
  none when nothing is new."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.email.mail :as mail]
            [s-exp.jumble.email.outbox :as outbox]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.view.i18n :as i18n]))

(set! *warn-on-reflection* true)

(def ^:private week (* 7 24 60 60 1000))

(def ^:private max-listed
  "Listings an email names; it says how many more there are."
  20)

(def ^:private batch-size
  "Members considered in one delivery round: the rest wait for the next."
  50)

(defn- new-listings
  "The [[max-listed]] newest listings on the board, not `user-id`'s, posted
  in (`after`, `until`], each with `:total`, how many there are in all."
  [db user-id after until]
  (jdbc/execute! db
                 [(str "SELECT id, title, count(*) OVER () AS total FROM listing
                           WHERE status IN "
                       listing/on-board-sql
                       " AND user_id <> ?
                             AND created_at > ? AND created_at <= ?
                           ORDER BY created_at DESC, id DESC
                           LIMIT ?")
                  user-id
                  after
                  until
                  max-listed]
                 db/opts))

(defn weekly-email
  "The weekly email to `to` about `listings`, the newest first, of the
  `n` new ones."
  [{:keys [base-url app-name]} to listings n]
  {:to to
   :subject (i18n/tn "This week on {app-name}: {n} new listing"
                     "This week on {app-name}: {n} new listings"
                     n
                     {:app-name app-name})
   :text (str (str/join "\n"
                        (for [{:keys [id title]} (take max-listed listings)]
                          (str "- " (mail/one-line title) ": " base-url "/listings/" id)))
              (when (> n max-listed)
                (str "\n\n" (i18n/t "And {n} more: {url}" {:n (- n max-listed) :url (str base-url "/listings")})))
              "\n\n-- \n"
              (i18n/t "You get this email once a week when something is new on {app-name}." {:app-name app-name})
              "\n"
              (i18n/t "Turn it off in your settings: {url}" {:url (str base-url "/settings")})
              "\n")})

(defn send-due!
  "Emails, through the outbox and in their language, each member due what
  others posted since they were last considered (a week ago at most), up to
  [[listing/settle-time]] ago, then marks them considered up to then;
  someone no longer allowed in, or with nothing new, is only marked. Runs
  every delivery round; writes only for members due."
  [db settings now]
  (when-let [due (seq (jdbc/execute! db
                                     ["SELECT id, email, language, coalesce(digest_sent_at, created_at) AS since
                                          FROM user
                                          WHERE weekly_digest = 1 AND deleted_at IS NULL
                                            AND coalesce(digest_sent_at, created_at) <= ?
                                          LIMIT ?"
                                      (- now week)
                                      batch-size]
                                     db/opts))]
    (let [allowed (account/allowed db (:owner settings))
          until (- now listing/settle-time)]
      (outbox/enqueue-each!
       due
       (fn [{:keys [id email language since]}]
         (let [found (when (allowed email) (new-listings db id (max since (- until week)) until))]
           (db/with-write-tx [tx db]
             ;; Unless they turned it off, or their account's deletion
             ;; started, meanwhile.
             (when (and (seq found)
                        (jdbc/execute-one! tx
                                           ["SELECT 1 FROM user WHERE id = ? AND weekly_digest = 1
                                                  AND deleted_at IS NULL"
                                            id]))
               (outbox/enqueue! tx (i18n/speaking language (weekly-email settings email found (:total (first found)))) now))
             (jdbc/execute-one! tx ["UPDATE user SET digest_sent_at = ? WHERE id = ?" until id]))))
       #(select-keys % [:id])))))
