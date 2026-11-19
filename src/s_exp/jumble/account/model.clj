(ns s-exp.jumble.account.model
  "Accounts, sessions and who may sign in: members, the allowlist moderators
  keep, removed addresses, and the one-use links and attempts of sign-in."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [s-exp.ex :as ex]
            [s-exp.jumble.account.membership :as membership]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.error :as error]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.email.outbox :as outbox]
            [s-exp.jumble.web.params :as params]
            [s-exp.jumble.token :as token]))

(set! *warn-on-reflection* true)

(def ^:private renewal-interval
  "Sessions are pushed back to a full ttl at most this often, so steady use
  costs one write a day rather than one per request; half their ttl, for
  shorter ones."
  (* 24 60 60 1000))

(def max-session-age
  "However often it is renewed, a session ends this long after sign-in, so
  even someone the sign-in service still lets in signs in again now and
  then."
  (* 30 24 60 60 1000))

(defn member-count
  "Accounts not deleted."
  [db]
  (:n (jdbc/execute-one! db ["SELECT count(*) AS n FROM user WHERE deleted_at IS NULL"] db/opts)))

(defn- check-room!
  "Throws ex-info of type ::error/community-full when the user identified by
  `iss` and `sub` has no account yet and the community already has
  `member-limit` members (none when nil)."
  [tx iss sub member-limit]
  (when (and member-limit
             (not (jdbc/execute-one! tx ["SELECT 1 FROM user WHERE iss = ? AND sub = ?" iss sub]))
             (>= (member-count tx) member-limit))
    (throw (ex/ex-info "Community full" ::error/community-full))))

(defn deleted-account
  "The id of the account `iss` and `sub` when its deletion is under way, or
  stopped part way (see data/delete-account!), else nil."
  [db iss sub]
  (:id (jdbc/execute-one! db
                          ["SELECT id FROM user WHERE iss = ? AND sub = ? AND deleted_at IS NOT NULL" iss sub]
                          db/opts)))

(defn- signed-in
  "`user`, the row a sign-in returned: none when its account is being
  deleted, which never signs in again."
  [user]
  (or user (throw (ex/ex-info "Account being deleted" ::error/account-deleted))))

(defn allow-address!
  "Lets the one address `email` in from `now` on, within transaction `tx`,
  the `way` it came (see 032-allowlist-way.sql), from `added-by` if anyone.
  An address already in stays as it was. Throws ex-info of type
  ::error/invalid-pattern for a pattern, which would let in others."
  [tx {:keys! [email way now] :keys [added-by]}]
  (membership/address! email)
  (jdbc/execute-one! tx
                     ["INSERT INTO allowed_email (pattern, added_by, added_at, way) VALUES (?, ?, ?, ?)
                          ON CONFLICT (pattern) DO NOTHING"
                      (str/lower-case email)
                      added-by
                      now
                      way]))

(defn upsert-user!
  "Creates or updates the user identified by `iss` and `sub`, refreshing
  email and name from the identity. Returns `{:id :email :name}`. Creating
  one past `member-limit` throws, see [[check-room!]]; so does an account
  being deleted, see [[signed-in]]. When `joining` by the join link, its
  address is let in with it, see [[allow-address!]]."
  ([db claims now] (upsert-user! db claims now nil))
  ([db {:keys! [iss sub ^String email name]} now {:keys [member-limit joining]}]
   (db/with-write-tx [tx db]
     (check-room! tx iss sub member-limit)
     (when joining
       (allow-address! tx {:email email :way "link" :now now}))
     (signed-in (jdbc/execute-one! tx
                                   ["INSERT INTO user (iss, sub, email, name, created_at, last_login_at)
                                        VALUES (?, ?, ?, ?, ?, ?)
                                        ON CONFLICT (iss, sub) DO UPDATE
                                        SET email = excluded.email,
                                            name = excluded.name,
                                            last_login_at = excluded.last_login_at
                                        WHERE deleted_at IS NULL
                                        RETURNING id, email, name"
                                    iss
                                    sub
                                    (str/lower-case email)
                                    name
                                    now
                                    now]
                                   db/opts)))))

(defn member
  "Member `id`, `{:id :name :email :created-at}`, while their account is not
  deleted; nil otherwise."
  [db id]
  (jdbc/execute-one! db
                     ["SELECT id, name, email, created_at FROM user WHERE id = ? AND deleted_at IS NULL" id]
                     db/opts))

(defn notify-messages
  "Whether `user-id` gets emails about unread messages."
  [db user-id]
  (= 1 (:notify-messages (jdbc/execute-one! db ["SELECT notify_messages FROM user WHERE id = ?" user-id] db/opts))))

(defn set-notify-messages!
  [db user-id on]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["UPDATE user SET notify_messages = ? WHERE id = ?" (if on 1 0) user-id])))

(defn weekly-digest
  "Whether `user-id` gets the weekly email of what is new."
  [db user-id]
  (= 1 (:weekly-digest (jdbc/execute-one! db ["SELECT weekly_digest FROM user WHERE id = ?" user-id] db/opts))))

(defn set-weekly-digest!
  [db user-id on]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["UPDATE user SET weekly_digest = ? WHERE id = ?" (if on 1 0) user-id])))

(defn language-of
  "The language the member with `email` reads Jumble in, nil (the
  community's) when they chose none or there is no such member."
  [db email]
  (:language (jdbc/execute-one! db
                                ["SELECT language FROM user WHERE email = ? AND deleted_at IS NULL"
                                 (str/lower-case email)]
                                db/opts)))

(defn set-language!
  "Sets the language `user-id` reads Jumble in; nil follows the community's."
  [db user-id language]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["UPDATE user SET language = ? WHERE id = ?" language user-id])))

(defn email-user!
  "The user signing in by email as `email`, created on first sign-in with the
  address as name. Unlike [[upsert-user!]], the name stays as its owner set
  it. Returns `{:id :email :name}`. Creating one past `member-limit` throws,
  see [[check-room!]]; so does an account being deleted, see [[signed-in]].
  When `joining` by the join link, its address is let in with it, see
  [[allow-address!]]."
  ([db email now] (email-user! db email now nil))
  ([db email now {:keys [member-limit joining]}]
   (db/with-write-tx [tx db]
     (check-room! tx "email" email member-limit)
     (when joining
       (allow-address! tx {:email email :way "link" :now now}))
     (signed-in (jdbc/execute-one! tx
                                   ["INSERT INTO user (iss, sub, email, name, created_at, last_login_at)
                                        VALUES ('email', ?, ?, ?, ?, ?)
                                        ON CONFLICT (iss, sub) DO UPDATE SET last_login_at = excluded.last_login_at
                                        WHERE deleted_at IS NULL
                                        RETURNING id, email, name"
                                    email
                                    email
                                    email
                                    now
                                    now]
                                   db/opts)))))

(defn validate-name
  "A display name typed in a form: `{:name trimmed}` or `{:errors {:name message}}`."
  [name]
  (let [name (params/trimmed name)]
    (cond
      (empty? name) {:errors {:name (i18n/t "Enter your name.")}}
      (> (count name) 80) {:errors {:name (i18n/t "Keep your name to 80 characters or fewer.")}}
      :else {:name name})))

(defn set-name!
  [db user-id name]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["UPDATE user SET name = ? WHERE id = ?" name user-id])))

(defn anyone-allowed?
  "Whether moderators let anyone sign in, rather than the allowlist only."
  [db]
  (= "on"
     (:value (jdbc/execute-one! db ["SELECT value FROM community_setting WHERE name = 'anyone-allowed'"] db/opts))))

(defn set-anyone-allowed!
  "Lets anyone sign in, or only the allowlist again, which is kept meanwhile."
  [db anyone]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx
                       ["INSERT INTO community_setting (name, value) VALUES ('anyone-allowed', ?)
                            ON CONFLICT (name) DO UPDATE SET value = excluded.value"
                        (if anyone "on" "off")])))

(defn allowed-emails
  "Who may sign in, as a rule of [[membership/allows?]]: `:anyone` when
  moderators let anyone in, else the `moderators`' addresses, always let in,
  then the allowlist's patterns."
  [db moderators]
  (if (anyone-allowed? db)
    :anyone
    (into (vec (sort moderators))
          (comp (map :pattern) (remove (set moderators)))
          (jdbc/execute! db ["SELECT pattern FROM allowed_email ORDER BY pattern"] db/opts))))

(defn mailbox
  "The mailbox `email` reaches, lowercase: `Alice+tag@example.org` and
  `alice@example.org` are one, for rate limits and removals."
  [email]
  (-> email str/lower-case (str/replace #"\+[^@]*@" "@")))

(defn removed?
  "Whether the moderators removed the member with address `email`, or
  another of its mailbox: they never sign in again."
  [db email]
  (some? (jdbc/execute-one! db ["SELECT 1 FROM removed_email WHERE email = ?" (mailbox email)])))

(defn allowed
  "Whether an email address may sign in, as a function of it: let in by
  [[allowed-emails]] and not removed (see [[removed?]]), read once, for
  checking many."
  [db moderators]
  (let [rule (allowed-emails db moderators)
        removed (into #{} (map :email) (jdbc/execute! db ["SELECT email FROM removed_email"] db/opts))]
    #(and (membership/allows? rule %) (not (removed (mailbox %))))))

(defn- like-escaped
  "`s` matching itself in a LIKE pattern escaped with `\\`."
  [s]
  (str/replace s #"[\\%_]" #(str "\\" %)))

(defn members-with-email
  "The accounts of the mailbox of `email` (see [[mailbox]]), `{:id :name
  :email :listings}` with how many listings each has: one, or one per
  address of the mailbox and way of signing in it was used with."
  [db email]
  (when-let [[_ local domain] (re-matches #"([^@]+)@(.+)" (mailbox email))]
    (jdbc/execute! db
                   ["SELECT u.id, u.name, u.email, (SELECT count(*) FROM listing l WHERE l.user_id = u.id) AS listings
                        FROM user u WHERE (u.email = ? OR u.email LIKE ? ESCAPE '\\') AND u.deleted_at IS NULL
                        ORDER BY u.id"
                    (str local "@" domain)
                    (str (like-escaped local) "+%@" (like-escaped domain))]
                   db/opts)))

(defn added-emails
  "The allowlist's patterns: with who added each, when, and the way it came
  in (see 032-allowlist-way.sql)."
  [db]
  (jdbc/execute! db
                 ["SELECT a.pattern, u.name AS added_by, a.added_at, a.way
                      FROM allowed_email a LEFT JOIN user u ON u.id = a.added_by
                      ORDER BY a.pattern"]
                 db/opts))

(defn allow!
  "Lets `patterns` (valid, lowercase) sign in, as moderator `moderator-id`.
  Patterns already there are left as they were. For each one added,
  `invite-for` may give an email to queue with it."
  [db {:keys [patterns moderator-id now invite-for]}]
  (db/with-write-tx [tx db]
    (doseq [pattern patterns
            :when (jdbc/execute-one! tx
                                     ["INSERT INTO allowed_email (pattern, added_by, added_at) VALUES (?, ?, ?)
                                          ON CONFLICT (pattern) DO NOTHING RETURNING pattern"
                                      pattern
                                      moderator-id
                                      now])
            :let [invitation (some-> invite-for (apply [pattern]))]
            :when invitation]
      (outbox/enqueue! tx invitation now))))

(defn disallow!
  [db pattern]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["DELETE FROM allowed_email WHERE pattern = ?" pattern])))

(defn create-session!
  "Starts a session for `user-id` and returns its token, the cookie value."
  [db user-id now ttl]
  (let [t (token/generate)]
    (db/with-write-tx [tx db]
      (jdbc/execute-one! tx
                         ["INSERT INTO session (token_hash, user_id, created_at, expires_at)
                              VALUES (?, ?, ?, ?)"
                          (token/digest t)
                          user-id
                          now
                          (+ now ttl)]))
    t))

(defn session-user
  "The user of live session `t`, `{:id :email :name}`, or nil; reading only,
  for pages that merely show who is signed in."
  [db t now]
  (when (seq t)
    (jdbc/execute-one! db
                       ["SELECT u.id, u.email, u.name, u.language
                            FROM session s JOIN user u ON u.id = s.user_id
                            WHERE s.token_hash = ? AND s.expires_at > ? AND s.created_at > ? AND u.deleted_at IS NULL"
                        (token/digest t)
                        now
                        (- now max-session-age)]
                       db/opts)))

(defn resume-session!
  "For the live session `t`, returns `{:user {:id :email :name} :renewed bool}`,
  else nil. Pushes the session's expiry back to `ttl` from `now` when it was
  last renewed over a day ago, or half `ttl`; `:renewed` tells whether it did."
  [db t now ttl]
  (when (seq t)
    (let [hash (token/digest t)]
      (when-let [{:keys [expires-at] :as row}
                 (jdbc/execute-one! db
                                    ["SELECT u.id, u.email, u.name, u.language, s.expires_at
                                         FROM session s JOIN user u ON u.id = s.user_id
                                         WHERE s.token_hash = ? AND s.expires_at > ? AND s.created_at > ? AND u.deleted_at IS NULL"
                                     hash
                                     now
                                     (- now max-session-age)]
                                    db/opts)]
        (let [renew (< (- expires-at now) (- ttl (min renewal-interval (quot ttl 2))))]
          (when renew
            (db/with-write-tx [tx db]
              (jdbc/execute-one! tx
                                 ["UPDATE session SET expires_at = ? WHERE token_hash = ?"
                                  (+ now ttl)
                                  hash])))
          {:user (dissoc row :expires-at)
           :renewed renew})))))

(defn delete-session!
  [db t]
  (when (seq t)
    (db/with-write-tx [tx db]
      (jdbc/execute-one! tx ["DELETE FROM session WHERE token_hash = ?" (token/digest t)]))))

(defn delete-expired-sessions!
  [db now]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["DELETE FROM session WHERE expires_at <= ?" now])))

(def ^:private links-per-address
  "Sign-in links one address may be sent while earlier ones are valid."
  3)

(def ^:private links-overall
  "Sign-in links sent to addresses without an account while earlier ones are
  valid: a bound on what Jumble can be made to send to strangers, which
  members, limited per address only, cannot be locked out by."
  100)

(defn request-email-sign-in!
  "Creates a sign-in link for `email`, valid `ttl` ms, and queues the email
  `(email-for token)` that carries it. Returns `:sent`, or `:limited` when
  too many links went to that address, or, for an address without an
  account, to such addresses in the last `ttl` ms. Expired requests are
  purged on the way. A `join-token` (the join link's) goes with the link,
  for the address to join when it is used."
  [db {:keys! [email return-to now ttl email-for] :keys [join-token]}]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["DELETE FROM email_sign_in WHERE expires_at <= ?" now])
    (let [count-of #(:n (jdbc/execute-one! tx % db/opts))]
      (if (or (>= (count (filter #(= (mailbox email) (mailbox (:email %)))
                                 (jdbc/execute! tx
                                                ["SELECT email FROM email_sign_in WHERE email LIKE ?"
                                                 (str "%" (subs email (str/last-index-of email "@")))]
                                                db/opts)))
                  links-per-address)
              (and (not (jdbc/execute-one! tx ["SELECT 1 FROM user WHERE email = ?" email]))
                   (>= (count-of ["SELECT count(*) AS n FROM email_sign_in e
                                   WHERE NOT EXISTS (SELECT 1 FROM user u WHERE u.email = e.email)"])
                       links-overall)))
        :limited
        (let [t (token/generate)]
          (jdbc/execute-one! tx
                             ["INSERT INTO email_sign_in (token_hash, email, return_to, created_at, expires_at, join_token)
                                  VALUES (?, ?, ?, ?, ?, ?)"
                              (token/digest t)
                              email
                              return-to
                              now
                              (+ now ttl)
                              join-token])
          (outbox/enqueue! tx (assoc (email-for t) :expires-at (+ now ttl)) now)
          :sent)))))

(defn email-sign-in
  "The address sign-in link `t` is for, while it is valid and unused."
  [db t now]
  (when (seq t)
    (:email (jdbc/execute-one! db
                               ["SELECT email FROM email_sign_in
                                    WHERE token_hash = ? AND used = 0 AND expires_at > ?"
                                (token/digest t)
                                now]
                               db/opts))))

(defn use-email-sign-in!
  "Marks sign-in link `t` used and returns `{:email :return-to :join-token}`,
  when it was valid and unused: each link signs in once."
  [db t now]
  (when (seq t)
    (db/with-write-tx [tx db]
      (when-let [row (jdbc/execute-one! tx
                                        ["UPDATE email_sign_in SET used = 1
                                             WHERE token_hash = ? AND used = 0 AND expires_at > ?
                                             RETURNING email, return_to, join_token"
                                         (token/digest t)
                                         now]
                                        db/opts)]
        ;; One sign-in spends the address's other links too.
        (jdbc/execute-one! tx ["UPDATE email_sign_in SET used = 1 WHERE email = ?" (:email row)])
        row))))

(defn create-login-attempt!
  "Records a sign-in in progress under `state`, with the join link's
  `join-token` when started from it. Expired attempts are purged on the
  way, so abandoned sign-ins do not accumulate."
  [db state {:keys! [nonce code-verifier return-to] :keys [join-token]} now ttl]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["DELETE FROM login_attempt WHERE expires_at <= ?" now])
    (jdbc/execute-one! tx
                       ["INSERT INTO login_attempt (state_hash, nonce, code_verifier, return_to, expires_at, join_token)
                            VALUES (?, ?, ?, ?, ?, ?)"
                        (token/digest state)
                        nonce
                        code-verifier
                        return-to
                        (+ now ttl)
                        join-token])))

(defn consume-login-attempt!
  "Removes the attempt for `state` and returns it if it had not expired, so
  each attempt completes at most once."
  [db state now]
  (when (seq state)
    (when-let [{:keys [expires-at] :as row}
               (db/with-write-tx [tx db]
                 (jdbc/execute-one! tx
                                    ["DELETE FROM login_attempt WHERE state_hash = ?
                                         RETURNING nonce, code_verifier, return_to, join_token, expires_at"
                                     (token/digest state)]
                                    db/opts))]
      (when (> expires-at now)
        (dissoc row :expires-at)))))
