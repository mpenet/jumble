(ns s-exp.jumble.account.join
  "The community's join link: whoever holds it may join, until moderators
  renew it, turn it off, or it expires. Joining by it lets the address in
  (see [[account/upsert-user!]] and [[account/email-user!]])."
  (:require [next.jdbc :as jdbc]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.token :as token]))

(set! *warn-on-reflection* true)

(def ^:private day (* 24 60 60 1000))

(def expiries
  "How long a join link may work, as moderators choose it, in the order
  offered: the form's value and the time, nil for never."
  [["never" nil] ["30" (* 30 day)] ["7" (* 7 day)]])

(defn create!
  "Makes the community's join link, in place of any earlier one, as
  moderator `moderator-id`, expiring `expires-in` ms after `now` or never
  when nil. Returns its token: moderators show and print it, so it is kept
  as it is, not as a digest."
  [db {:keys [moderator-id expires-in now]}]
  (let [t (token/generate)]
    (db/with-write-tx [tx db]
      (jdbc/execute-one! tx
                         ["INSERT INTO join_link (id, token, created_by, created_at, expires_at)
                              VALUES (1, ?, ?, ?, ?)
                              ON CONFLICT (id) DO UPDATE SET token = excluded.token,
                                created_by = excluded.created_by,
                                created_at = excluded.created_at,
                                expires_at = excluded.expires_at"
                          t
                          moderator-id
                          now
                          (some-> expires-in (+ now))]))
    t))

(defn current
  "The join link working at `now`, `{:token :created-at :expires-at
  :created-by}` (the moderator's name), or nil."
  [db now]
  (jdbc/execute-one! db
                     ["SELECT j.token, j.created_at, j.expires_at, u.name AS created_by
                          FROM join_link j LEFT JOIN user u ON u.id = j.created_by
                          WHERE j.expires_at IS NULL OR j.expires_at > ?"
                      now]
                     db/opts))

(defn valid?
  "Whether `t` is the join link working at `now`."
  [db t now]
  (token/same? t (:token (current db now))))

(defn turn-off!
  [db]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["DELETE FROM join_link"])))
