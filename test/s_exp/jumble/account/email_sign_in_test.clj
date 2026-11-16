(ns s-exp.jumble.account.email-sign-in-test
  "Signing in by emailed link: asking for one, its limits, using it once,
  and who may."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.account.data :as data]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def ^:private minute (* 60 1000))

(def ^:private ttl (* 15 minute))

(def ^:private email-mode
  {:settings {:sign-in "email" :allowed-emails ["*@example.com" "guest@gmail.com"]}})

(defn- request
  [method uri & {:as opts}]
  (fixtures/request method uri nil (merge {:ctx email-mode} opts)))

(defn- outbox
  []
  (jdbc/execute! *db*
                 ["SELECT recipient, subject, body FROM email_outbox ORDER BY id"]
                 {:builder-fn rs/as-unqualified-maps}))

(defn- link-token
  "The token in the sign-in link emailed last to `email`."
  [email]
  (some->> (outbox)
           (filter #(= email (:recipient %)))
           last
           :body
           (re-find #"/login/email\?token=([A-Za-z0-9_-]+)")
           second))

(defn- session-cookie
  [response]
  (some #(second (re-find #"^jumble_session=([^;]+)" %)) (get-in response [:headers "Set-Cookie"])))

(defn- user-emails
  []
  (mapv :email (jdbc/execute! *db* ["SELECT email FROM user ORDER BY id"] {:builder-fn rs/as-unqualified-maps})))

(deftest request-model-test
  (let [email-for (fn [token] {:to "alice@example.com" :subject "Sign in" :text (str "token " token)})
        request! #(account/request-email-sign-in! *db*
                                                  {:email "alice@example.com" :return-to "/" :now %
                                                   :ttl ttl :email-for email-for})]
    (testing "a request queues the email and stores only the token's hash"
      (is (= :sent (request! 0)))
      (let [token (second (re-find #"token (\S+)" (:body (first (outbox)))))]
        (is (some? token))
        (is (empty? (jdbc/execute! *db* ["SELECT 1 FROM email_sign_in WHERE token_hash = ?" token]))))
      (is (= [ttl]
             (map :expires_at
                  (jdbc/execute! *db*
                                 ["SELECT expires_at FROM email_outbox"]
                                 {:builder-fn rs/as-unqualified-maps})))
          "the email is of no use once the link expired"))
    (testing "at most three links per address in a quarter of an hour"
      (is (= [:sent :sent :limited] (mapv request! [minute (* 2 minute) (* 3 minute)])))
      (is (= 3 (count (outbox))))
      (is (= :sent (request! (+ ttl minute))) "and again once the first ones expired"))))

(deftest global-limit-test
  (let [request! #(account/request-email-sign-in! *db*
                                                  {:email %1 :return-to "/" :now %2 :ttl ttl
                                                   :email-for (fn [_] {:to %1 :subject "s" :text "t"})})]
    (account/email-user! *db* "alice@example.com" 0)
    (testing "at most 100 links in a quarter of an hour to addresses without an account"
      (let [results (mapv #(request! (str "user" % "@example.com") %) (range 101))]
        (is (= 100 (count (filter #{:sent} results))))
        (is (= :limited (peek results)))))
    (testing "which cannot lock members out: they have their own limit only"
      (is (= :sent (request! "alice@example.com" 200))))))

(deftest sign-in-page-test
  (testing "the sign-in page asks for an email address"
    (let [{:keys [status body]} (request :get "/login?return_to=%2Flistings")]
      (is (= 200 status))
      (is (re-find #"<form [^>]*method=\"post\" action=\"/login\"" body))
      (is (re-find #"<input [^>]*type=\"email\"[^>]*name=\"email\"" body))
      (is (str/includes? body "name=\"return_to\" value=\"/listings\""))))
  (testing "an address that is not one comes back with an explanation"
    (let [{:keys [status body]} (request :post "/login" :form {"email" "not an address"})]
      (is (= 422 status))
      (is (str/includes? body "is not an email address."))))
  (testing "OIDC instances keep sending people to the provider (unreachable in these tests)"
    (let [{:keys [status body]} (fixtures/request :get "/login" nil)]
      (is (= 503 status))
      (is (not (str/includes? body "name=\"email\""))))
    (is (= 404 (:status (fixtures/request :post "/login" nil :form {"email" "alice@example.com"}))))))

(deftest email-link-test
  (testing "asking for a link: the same answer for anyone, an email for members only"
    (let [member (request :post "/login" :form {"email" " Alice@Example.com " "return_to" "/listings"})
          stranger (request :post "/login" :form {"email" "eve@evil.example"})]
      (is (= 303 (:status member) (:status stranger)) "redirected, so reloading sends nothing again")
      (is (= "/login?sent" (get-in member [:headers "location"]) (get-in stranger [:headers "location"]))
          "nothing tells members from strangers")
      (is (str/includes? (:body (request :get "/login?sent")) "Check your inbox"))
      (is (= ["alice@example.com"] (mapv :recipient (outbox))))
      (is (str/includes? (:subject (first (outbox))) "Sign in to Jumble"))))
  (let [token (link-token "alice@example.com")
        link (str "/login/email?token=" token)]
    (testing "the link asks to confirm, so mail scanners following it do not use it up"
      (let [{:keys [status body] :as response} (request :get link)]
        (is (= 200 status))
        (is (nil? (session-cookie response)))
        (is (str/includes? body "alice@example.com"))
        (is (re-find #"<form [^>]*method=\"post\" action=\"/login/email\"" body))
        (is (str/includes? body (str "value=\"" token "\""))))
      (is (= 200 (:status (request :get link))) "still valid after being looked at"))
    (testing "confirming signs in, as the address, where the person was going"
      (let [signed-in (fixtures/counted "jumble_sign_ins_total{method=\"email\"}")
            response (request :post "/login/email" :form {"token" token})]
        (is (= 1.0 (- (fixtures/counted "jumble_sign_ins_total{method=\"email\"}") signed-in)) "counted, by method")
        (is (= 303 (:status response)))
        (is (= "/listings" (get-in response [:headers "location"])))
        (is (some? (session-cookie response))))
      (is (= ["alice@example.com"] (user-emails))))
    (testing "a link works once"
      (is (= 400 (:status (request :post "/login/email" :form {"token" token}))))
      (is (= 400 (:status (request :get link)))))
    (testing "signing in again finds the same account"
      (request :post "/login" :form {"email" "alice@example.com"})
      (request :post "/login/email" :form {"token" (link-token "alice@example.com")})
      (is (= ["alice@example.com"] (user-emails))))))

(deftest member-limit-test
  (let [limited (assoc-in email-mode [:settings :member-limit] 1)
        sign-in! (fn [email]
                   (request :post "/login" :form {"email" email} :ctx limited)
                   (request :post "/login/email" :form {"token" (link-token email)} :ctx limited))]
    (is (= 303 (:status (sign-in! "alice@example.com"))))
    (testing "a new member past the limit gets a link, which tells them the community is full"
      (let [{:keys [status body]} (sign-in! "bob@example.com")]
        (is (= 403 status))
        (is (str/includes? body "This community is full"))))
    (testing "members already there still sign in"
      (is (= 303 (:status (sign-in! "alice@example.com")))))
    (is (= ["alice@example.com"] (user-emails)))))

(deftest expired-and-revoked-links-test
  (request :post "/login" :form {"email" "guest@gmail.com"})
  (let [token (link-token "guest@gmail.com")]
    (testing "an address no longer allowed when the link is used is refused"
      (is (= 403
             (:status (fixtures/request :post
                                        "/login/email"
                                        nil
                                        :form {"token" token}
                                        :ctx {:settings {:sign-in "email" :allowed-emails ["*@example.com"]}}))))
      (is (empty? (user-emails))))
    (testing "expired links are refused"
      (db/with-write-tx [tx *db*]
        (jdbc/execute! tx ["UPDATE email_sign_in SET expires_at = 0"]))
      (is (= 400 (:status (request :post "/login/email" :form {"token" token}))))
      (is (= 400 (:status (request :get (str "/login/email?token=" token))))))
    (testing "and unknown ones"
      (is (= 400 (:status (request :post "/login/email" :form {"token" "nope"})))))))

(deftest repeated-fields-test
  (testing "a field sent twice reads as its first value, not as a failure"
    (let [response ((fixtures/handler email-mode)
                    {:request-method :post :uri "/login"
                     :headers {"origin" "http://localhost:8080"
                               "content-type" "application/x-www-form-urlencoded"}
                     :body (java.io.ByteArrayInputStream.
                            (.getBytes "email=alice%40example.com&email=bob%40example.com" "UTF-8"))})]
      (is (= 303 (:status response)))
      (is (= ["alice@example.com"] (mapv :recipient (outbox)))))))

(deftest address-checks-test
  (testing "addresses mail servers would read otherwise are refused"
    (doseq [bad ["a,b@example.com" "eve@.example.com" "eve(x)@example.com" "x%y@example.com" "x!y@example.com"
                 "Eve <eve@example.com>"]]
      (is (= 422 (:status (request :post "/login" :form {"email" bad}))) bad)))
  (testing "plus addresses are fine"
    (is (= 303 (:status (request :post "/login" :form {"email" "alice+jumble@example.com"}))))))

(deftest plus-addressing-limit-test
  (testing "tagged variants of one address share its limit"
    (doseq [tag (range 4)]
      (request :post "/login" :form {"email" (str "alice+" tag "@example.com")}))
    (is (= 3 (count (outbox))))))

(deftest one-link-per-sign-in-test
  (testing "using a link spends the address's other links"
    (request :post "/login" :form {"email" "alice@example.com"})
    (let [first-token (link-token "alice@example.com")]
      (request :post "/login" :form {"email" "alice@example.com"})
      (is (= 303 (:status (request :post "/login/email" :form {"token" (link-token "alice@example.com")}))))
      (is (= 400 (:status (request :post "/login/email" :form {"token" first-token})))))))

(deftest address-messages-test
  (testing "an empty field and a wrong address are told apart"
    (is (str/includes? (:body (request :post "/login" :form {"email" ""})) "Enter your email address."))
    (is (str/includes? (:body (request :post "/login" :form {"email" "alice@"})) "is not an email address"))))

(deftest interrupted-deletion-test
  (let [sign-in! (fn []
                   (request :post "/login" :form {"email" "alice@example.com"})
                   (request :post "/login/email" :form {"token" (link-token "alice@example.com")}))]
    (sign-in!)
    (let [alice (fixtures/user-id "alice@example.com")
          listing (:id (fixtures/listing! alice "Desk"))]
      ;; Deletion's first step alone, as when the rest of it failed.
      (jdbc/execute! *db* ["UPDATE user SET deleted_at = 1 WHERE id = ?" alice])
      (jdbc/execute! *db* ["DELETE FROM session WHERE user_id = ?" alice])
      (testing "signing in again finishes the deletion, then starts a new account"
        (let [response (sign-in!)]
          (is (= 303 (:status response)))
          (is (some? (session-cookie response))))
        (is (empty? (jdbc/execute! *db* ["SELECT 1 FROM listing WHERE id = ?" listing])))
        (is (= "deleted"
               (:iss (jdbc/execute-one! *db*
                                        ["SELECT iss FROM user WHERE id = ?" alice]
                                        {:builder-fn rs/as-unqualified-maps}))))
        (is (not= alice (fixtures/user-id "alice@example.com")))))))

(deftest removed-member-test
  (let [sign-in! (fn []
                   (request :post "/login" :form {"email" "alice@example.com"})
                   (request :post "/login/email" :form {"token" (link-token "alice@example.com")}))]
    (sign-in!)
    (request :post "/login" :form {"email" "alice@example.com"})
    (let [earlier-link (link-token "alice@example.com")]
      (data/remove-member! *db* (blob/memory-store) {:email "alice@example.com" :moderator-id nil :now 1})
      (testing "a removed member is sent no sign-in link"
        (request :post "/login" :form {"email" "alice@example.com"})
        (is (empty? (filter #(= "alice@example.com" (:recipient %)) (outbox)))))
      (testing "nor signs in with one sent before: it went with their account"
        (let [{:keys [status] :as response} (request :post "/login/email" :form {"token" earlier-link})]
          (is (= 400 status))
          (is (nil? (session-cookie response)))))
      (is (not-any? #{"alice@example.com"} (user-emails)) "no account made again"))))

(deftest client-limit-test
  (let [handler #(fixtures/handler (assoc-in email-mode [:settings :clients-known] %))
        ask (fn [h addr email]
              (h {:request-method :post :uri "/login" :remote-addr addr
                  :headers {"origin" "http://localhost:8080" "content-type" "application/x-www-form-urlencoded"}
                  :body (java.io.ByteArrayInputStream. (.getBytes (str "email=" email) "UTF-8"))}))
        sent-to #(set (map :recipient (outbox)))]
    (testing "served directly, one client gets at most five links a quarter of an hour, whatever the address"
      (let [h (handler true)]
        (doseq [i (range 7)]
          (is (= 303 (:status (ask h "203.0.113.9" (str "member" i "@example.com")))) "told the same either way"))
        (is (= 5 (count (outbox))))
        (is (not (contains? (sent-to) "member5@example.com")))
        (testing "other clients are not held back"
          (ask h "198.51.100.4" "member6@example.com")
          (is (contains? (sent-to) "member6@example.com")))
        (testing "an IPv6 client counts by its /64, which one holder usually has whole"
          (doseq [i (range 6)]
            (ask h (str "2001:db8:1:2::" (inc i)) (str "v6-" i "@example.com")))
          (is (= 5 (count (filter #(str/starts-with? % "v6-") (sent-to))))))))
    (testing "behind a proxy of one's own, every request comes from it: no limit by address"
      (let [h (handler false)]
        (doseq [i (range 7)]
          (ask h "127.0.0.1" (str "proxied" i "@example.com")))
        (is (= 7 (count (filter #(str/starts-with? % "proxied") (sent-to)))))))
    (testing "unless the proxy says each client's address"
      (let [h (fixtures/handler (update email-mode :settings assoc :clients-known true :behind-proxy true))
            ask-from (fn [client email]
                       (h {:request-method :post :uri "/login" :remote-addr "127.0.0.1"
                           :headers {"origin" "http://localhost:8080" "content-type" "application/x-www-form-urlencoded"
                                     "x-forwarded-for" client}
                           :body (java.io.ByteArrayInputStream. (.getBytes (str "email=" email) "UTF-8"))}))]
        (doseq [i (range 7)]
          (ask-from "203.0.113.50" (str "far" i "@example.com")))
        (ask-from "198.51.100.50" "near@example.com")
        (is (= 5 (count (filter #(str/starts-with? % "far") (sent-to)))))
        (is (contains? (sent-to) "near@example.com"))))))
