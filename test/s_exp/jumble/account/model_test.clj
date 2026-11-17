(ns s-exp.jumble.account.model-test
  "Accounts and sessions: creating and updating members, the community's
  limit, sessions sliding and ending, deleted accounts."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.account.data :as data]
            [s-exp.jumble.account.model :as account]
            [s-exp.ex :as ex]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.error :as-alias error]
            [s-exp.jumble.fixtures :refer [*db* db-fixture]]
            [s-exp.jumble.token :as token]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def t0 1761650000000)
(def day (* 24 60 60 1000))
(def ttl (* 7 day))

(def identity-claims
  {:iss "https://dex.example.com" :sub "u1" :email "Alice@Example.com" :name "Alice"})

(deftest upsert-user-test
  (let [user (account/upsert-user! *db* identity-claims t0)]
    (testing "creates the user, email lowercased"
      (is (= {:email "alice@example.com" :name "Alice"} (select-keys user [:email :name])))
      (is (integer? (:id user))))
    (testing "the same iss/sub updates the profile and keeps the id"
      (let [again (account/upsert-user! *db*
                                        (assoc identity-claims :email "alice@new.example.com" :name "Al")
                                        (+ t0 1000))]
        (is (= (:id user) (:id again)))
        (is (= {:email "alice@new.example.com" :name "Al"} (select-keys again [:email :name])))
        (is (= (+ t0 1000)
               (:last_login_at (jdbc/execute-one! *db*
                                                  ["SELECT last_login_at FROM user"]
                                                  {:builder-fn rs/as-unqualified-maps}))))))
    (testing "another subject is another user"
      (is (not= (:id user) (:id (account/upsert-user! *db* (assoc identity-claims :sub "u2") t0)))))))

(defn- refused
  "The type of error `f` was refused with, nil when it was not."
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex/ex-type e))))

(deftest member-limit-test
  (let [join! #(account/upsert-user! *db*
                                     (assoc identity-claims :sub % :email (str % "@example.com"))
                                     t0
                                     {:member-limit 2})]
    (join! "ann")
    (join! "bob")
    (testing "past the limit, no account is created"
      (is (= ::error/community-full (refused #(join! "carol"))))
      (is (= ::error/community-full (refused #(account/email-user! *db* "dan@example.com" t0 {:member-limit 2}))))
      (is (= 2 (account/member-count *db*))))
    (testing "members already there still sign in"
      (is (= "ann@example.com" (:email (join! "ann")))))
    (testing "without a limit, anyone allowed joins"
      (is (some? (account/email-user! *db* "dan@example.com" t0))))
    (testing "a deleted account frees its place"
      (data/delete-account! *db* (blob/memory-store) (:id (join! "ann")) t0)
      (is (= 2 (account/member-count *db*)))
      (data/delete-account! *db* (blob/memory-store) (:id (account/email-user! *db* "dan@example.com" t0)) t0)
      (is (= "carol@example.com" (:email (join! "carol")))))))

(deftest session-test
  (let [user (account/upsert-user! *db* identity-claims t0)
        token (account/create-session! *db* (:id user) t0 ttl)]
    (testing "only the token's digest is stored"
      (is (= 1
             (:n (jdbc/execute-one! *db*
                                    ["SELECT count(*) AS n FROM session WHERE token_hash = ?"
                                     (token/digest token)])))))
    (testing "a live session resolves to its user, with the language they chose (none yet)"
      (is (= (assoc user :language nil) (:user (account/resume-session! *db* token (+ t0 day) ttl)))))
    (testing "unknown, malformed and missing tokens resolve to nothing"
      (is (nil? (account/resume-session! *db* (token/generate) t0 ttl)))
      (is (nil? (account/resume-session! *db* "" t0 ttl)))
      (is (nil? (account/resume-session! *db* nil t0 ttl))))
    (testing "expired sessions resolve to nothing"
      (is (nil? (account/resume-session! *db* token (+ t0 ttl) ttl))))
    (testing "deleted sessions resolve to nothing"
      (let [token (account/create-session! *db* (:id user) t0 ttl)]
        (account/delete-session! *db* token)
        (is (nil? (account/resume-session! *db* token t0 ttl)))))))

(defn- expires-at
  [token]
  (:expires_at (jdbc/execute-one! *db*
                                  ["SELECT expires_at FROM session WHERE token_hash = ?"
                                   (token/digest token)]
                                  {:builder-fn rs/as-unqualified-maps})))

(deftest sliding-expiry-test
  (let [user (account/upsert-user! *db* identity-claims t0)
        token (account/create-session! *db* (:id user) t0 ttl)]
    (testing "use within a day of the last renewal does not write"
      (is (false? (:renewed (account/resume-session! *db* token (+ t0 (dec day)) ttl))))
      (is (= (+ t0 ttl) (expires-at token))))
    (testing "later use pushes expiry to a full ttl from now"
      (is (true? (:renewed (account/resume-session! *db* token (+ t0 (* 3 day)) ttl))))
      (is (= (+ t0 (* 3 day) ttl) (expires-at token))))))

(deftest delete-expired-sessions-test
  (let [user (account/upsert-user! *db* identity-claims t0)
        old (account/create-session! *db* (:id user) t0 ttl)
        fresh (account/create-session! *db* (:id user) (+ t0 ttl) ttl)]
    (account/delete-expired-sessions! *db* (+ t0 ttl))
    (is (nil? (expires-at old)))
    (is (some? (expires-at fresh)))))

(def attempt-ttl (* 10 60 1000))

(deftest login-attempt-test
  (let [attempt {:nonce "n" :code-verifier "v" :return-to "/listings/1" :join-token "j"}
        state (token/generate)]
    (account/create-login-attempt! *db* state attempt t0 attempt-ttl)
    (testing "consuming returns what was stored, once"
      (is (= attempt (account/consume-login-attempt! *db* state (+ t0 1000))))
      (is (nil? (account/consume-login-attempt! *db* state (+ t0 1000)))))
    (testing "expired attempts cannot be consumed"
      (let [state (token/generate)]
        (account/create-login-attempt! *db* state attempt t0 attempt-ttl)
        (is (nil? (account/consume-login-attempt! *db* state (+ t0 attempt-ttl))))))
    (testing "unknown states cannot be consumed"
      (is (nil? (account/consume-login-attempt! *db* (token/generate) t0)))
      (is (nil? (account/consume-login-attempt! *db* nil t0))))
    (testing "creating an attempt purges expired ones"
      (account/create-login-attempt! *db* (token/generate) attempt (+ t0 (* 2 attempt-ttl)) attempt-ttl)
      (is (= 1 (:n (jdbc/execute-one! *db* ["SELECT count(*) AS n FROM login_attempt"])))))))

(deftest session-lifetime-test
  (let [user (account/upsert-user! *db* identity-claims t0)
        token (account/create-session! *db* (:id user) t0 ttl)]
    (testing "daily use keeps a session going, but not beyond 30 days from sign-in"
      (doseq [d (range 1 30)]
        (is (some? (account/resume-session! *db* token (+ t0 (* d day)) ttl)) (str "day " d)))
      (is (nil? (account/resume-session! *db* token (+ t0 (* 30 day)) ttl))))))

(deftest deleted-account-test
  (let [{:keys [id]} (account/upsert-user! *db* identity-claims t0)
        email-id (:id (account/email-user! *db* "bob@example.com" t0))
        tokens [(account/create-session! *db* id t0 ttl) (account/create-session! *db* email-id t0 ttl)]]
    ;; Deletion's first step alone, as when the rest of it failed.
    (jdbc/execute! *db* ["UPDATE user SET deleted_at = ?" (+ t0 1)])
    (testing "an account being deleted keeps no session"
      (doseq [t tokens]
        (is (nil? (account/session-user *db* t (+ t0 2))))
        (is (nil? (account/resume-session! *db* t (+ t0 2) ttl)))))
    (testing "an account being deleted does not sign in again"
      (is (= ::error/account-deleted (refused #(account/upsert-user! *db* identity-claims (+ t0 2)))))
      (is (= ::error/account-deleted (refused #(account/email-user! *db* "bob@example.com" (+ t0 2))))))))

(deftest one-day-session-test
  (let [{:keys [id]} (account/upsert-user! *db* identity-claims t0)
        hour (* 60 60 1000)
        token (account/create-session! *db* id t0 day)]
    (testing "a one-day session slides too, renewed after half a day"
      (is (false? (:renewed (account/resume-session! *db* token (+ t0 (* 11 hour)) day))))
      (is (true? (:renewed (account/resume-session! *db* token (+ t0 (* 13 hour)) day))))
      (is (some? (account/resume-session! *db* token (+ t0 (* 30 hour)) day)) "past its first day"))))

(defn- allowlist
  []
  (jdbc/execute! *db* ["SELECT pattern, way FROM allowed_email ORDER BY pattern"] {:builder-fn rs/as-unqualified-maps}))

(deftest joining-test
  (testing "an account made by joining lets its address in from then on, as joined by the link"
    (account/email-user! *db* "Ann@Example.org" t0 {:joining true})
    (account/upsert-user! *db* (assoc identity-claims :sub "u2" :email "Bea@Example.org") t0 {:joining true})
    (is (= [{:pattern "ann@example.org" :way "link"} {:pattern "bea@example.org" :way "link"}] (allowlist)))
    (is ((account/allowed *db* ["*@club.example"]) "ann@example.org")))
  (testing "no account, no address let in: both or neither"
    (is (= ::error/community-full
           (refused #(account/email-user! *db* "cy@example.org" t0 {:member-limit 2 :joining true}))))
    (is (= 2 (count (allowlist)))))
  (testing "only one address joins: never a pattern letting in others"
    (is (= ::error/invalid-pattern (refused #(account/email-user! *db* "*@club.example" t0 {:joining true}))))
    (is (= 0 (:n (jdbc/execute-one! *db* ["SELECT count(*) AS n FROM user WHERE email = '*@club.example'"])))))
  (testing "an address already let in stays as it was"
    (account/allow! *db* {:patterns ["dan@example.org"] :moderator-id nil :now 1})
    (account/email-user! *db* "dan@example.org" t0 {:joining true})
    (is (some #{{:pattern "dan@example.org" :way "moderator"}} (allowlist)))))
