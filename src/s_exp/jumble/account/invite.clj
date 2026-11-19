(ns s-exp.jumble.account.invite
  "Members inviting people by email, when moderators let them: a few a day
  each, so many a day in all. An invitation lets its address in, in the
  allowlist as the member's; it is forgotten 90 days after."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [s-exp.jumble.account.membership :as membership]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.email.outbox :as outbox]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.store.switch :as switch]))

(set! *warn-on-reflection* true)

(def per-day
  "Invitations a member may send a day: a bound on what one account, in its
  owner's hands or a thief's, can make the community send to strangers."
  5)

(def ^:private day (* 24 60 60 1000))

(def ^:private kept
  "How long an invitation is kept: its member sees it meanwhile."
  (* 90 day))

(defn open?
  "Whether moderators let members invite people."
  [db]
  (switch/on? db "member-invitations"))

(defn set-open!
  [db open]
  (switch/turn! db "member-invitations" open))

(defn invite!
  "Invites `email` (valid) from member `member-id`: lets it in, as the
  member's invitation, unless [[account/allowed]] does already (with the
  community's `owner`), and queues the email `(email-for address)`.
  Returns `:sent`; `:limited` past [[per-day]] from the member in the last
  day, or `:community-limited` past `community-per-day` from all members. An
  address of a member's mailbox or a removed one is neither emailed nor let
  in, with the same result: whoever invites learns nothing of either.
  Throws ex-info of type ::error/invalid-pattern for a pattern, which would
  let in others."
  [db {:keys! [member-id email now owner community-per-day email-for]}]
  (let [email (str/lower-case (membership/address! email))]
    (db/with-write-tx [tx db]
      (cond
        (>= (:n (jdbc/execute-one! tx
                                   ["SELECT count(*) AS n FROM invitation WHERE user_id = ? AND sent_at > ?"
                                    member-id
                                    (- now day)]
                                   db/opts))
            per-day)
        :limited

        (>= (:n (jdbc/execute-one! tx ["SELECT count(*) AS n FROM invitation WHERE sent_at > ?" (- now day)] db/opts))
            community-per-day)
        :community-limited

        :else
        (do (jdbc/execute-one! tx
                               ["INSERT INTO invitation (user_id, email, sent_at) VALUES (?, ?, ?)"
                                member-id
                                email
                                now])
            (when-not (or (account/removed? tx email)
                          (seq (account/members-with-email tx email)))
              (when-not ((account/allowed tx owner) email)
                (account/allow-address! tx {:email email :way "invitation" :added-by member-id :now now}))
              (outbox/enqueue! tx (email-for email) now))
            :sent)))))

(defn forget-old!
  "Forgets the invitations sent [[kept]] ago or more; whom they let in stays."
  [db now]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["DELETE FROM invitation WHERE sent_at <= ?" (- now kept)])))

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
