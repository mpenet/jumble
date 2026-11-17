(ns s-exp.jumble.account.invite
  "Members inviting people by email, when moderators let them: a few a day
  each. An invitation lets its address in, in the allowlist as the member's."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [s-exp.jumble.account.membership :as membership]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.email.outbox :as outbox]
            [s-exp.jumble.store.db :as db]))

(set! *warn-on-reflection* true)

(def per-day
  "Invitations a member may send a day: a bound on what one account, in its
  owner's hands or a thief's, can make the community send to strangers."
  5)

(def ^:private day (* 24 60 60 1000))

(defn open?
  "Whether moderators let members invite people."
  [db]
  (= "on"
     (:value (jdbc/execute-one! db ["SELECT value FROM community_setting WHERE name = 'member-invitations'"] db/opts))))

(defn set-open!
  [db open]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx
                       ["INSERT INTO community_setting (name, value) VALUES ('member-invitations', ?)
                            ON CONFLICT (name) DO UPDATE SET value = excluded.value"
                        (if open "on" "off")])))

(defn invite!
  "Invites `email` (valid) from member `member-id`: lets it in, as the
  member's invitation, unless the `configured` rule or the allowlist does
  already, and queues the email `(email-for address)`. Returns `:sent`, or
  `:limited` past [[per-day]] in the last day. A member's address or a
  removed one is neither emailed nor let in, with the same result: whoever
  invites learns nothing of either. Throws ex-info of type
  ::error/invalid-pattern for a pattern, which would let in others."
  [db {:keys! [member-id email now configured email-for]}]
  (let [email (str/lower-case (membership/address! email))]
    (db/with-write-tx [tx db]
      (if (>= (:n (jdbc/execute-one! tx
                                     ["SELECT count(*) AS n FROM invitation WHERE user_id = ? AND sent_at > ?"
                                      member-id
                                      (- now day)]
                                     db/opts))
              per-day)
        :limited
        (do (jdbc/execute-one! tx
                               ["INSERT INTO invitation (user_id, email, sent_at) VALUES (?, ?, ?)"
                                member-id
                                email
                                now])
            (when-not (or (account/removed? tx email)
                          (jdbc/execute-one! tx ["SELECT 1 FROM user WHERE email = ? AND deleted_at IS NULL" email]))
              (when-not ((account/allowed tx configured) email)
                (account/allow-address! tx {:email email :way "invitation" :added-by member-id :now now}))
              (outbox/enqueue! tx (email-for email) now))
            :sent)))))

(defn sent
  "The invitations member `member-id` sent, latest first: `{:email :sent-at
  :joined}`, joined when an account with the address was made since."
  [db member-id]
  (mapv #(update % :joined pos?)
        (jdbc/execute! db
                       ["SELECT i.email, i.sent_at,
                                EXISTS (SELECT 1 FROM user u WHERE u.email = i.email AND u.created_at >= i.sent_at) AS joined
                            FROM invitation i WHERE i.user_id = ? ORDER BY i.sent_at DESC, i.id DESC"
                        member-id]
                       db/opts)))
