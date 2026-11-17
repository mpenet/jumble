(ns s-exp.jumble.account.controller-test
  "Signing in with a provider, through the controller: the callback's checks,
  who may sign in, the community's limit, failures, signing out."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [s-exp.jumble.account.data :as data]
            [s-exp.jumble.account.invite :as invite]
            [s-exp.jumble.account.join :as join]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.fake-idp :as idp]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]
            [s-exp.jumble.oidc :as oidc])
  (:import (java.net URI)))

(set! *warn-on-reflection* true)

(def base-url "http://jumble.test")
(def ttl fixtures/session-ttl)

(def ^:dynamic *idp* nil)
(def ^:dynamic *handler* nil)

(defn- app
  [idp settings]
  (fixtures/handler {:oidc (oidc/provider {:issuer (:issuer idp)
                                           :client-id idp/client-id
                                           :client-secret idp/client-secret
                                           :redirect-uri (str base-url "/auth/callback")})
                     :settings (merge {:base-url base-url
                                       :session-ttl ttl
                                       :sign-in "oidc"
                                       :allowed-emails ["*@example.com"]}
                                      settings)}))

(use-fixtures :each
  db-fixture
  (fn [t]
    (let [p (idp/start!)]
      (try
        (binding [*idp* p *handler* (app p nil)]
          (t))
        (finally
          (idp/stop! p))))))

(defn- request
  [method ^String url cookies & {:keys [headers]}]
  (let [uri (URI. url)]
    (*handler* {:request-method method
                :uri (.getRawPath uri)
                :query-string (.getRawQuery uri)
                :headers (cond-> (or headers {})
                           (seq cookies) (assoc "cookie" (str/join "; " (map (fn [[k v]] (str k "=" v)) cookies))))})))

(defn- set-cookies
  "name -> value of the Set-Cookie headers in `response`."
  [response]
  (into {}
        (map #(let [[_ k v] (re-find #"^([^=]+)=([^;]*)" %)] [k v]))
        (get-in response [:headers "Set-Cookie"])))

(defn- location [response] (get-in response [:headers "location"]))

(defn- sign-in
  "Runs the browser side of a sign-in. Returns the callback response."
  ([return-to] (sign-in return-to nil))
  ([return-to claims]
   (let [login (request :get (str "/login" (some->> return-to (str "?return_to="))) nil)
         callback (idp/authorize! *idp* (location login) claims)]
     (request :get callback (set-cookies login)))))

(defn- session-user
  [response]
  (:user (account/resume-session! *db*
                                  (get (set-cookies response) "jumble_session")
                                  (System/currentTimeMillis)
                                  ttl)))

(defn- user-count []
  (:n (jdbc/execute-one! *db* ["SELECT count(*) AS n FROM user"])))

(deftest sign-in-test
  (let [response (sign-in "%2Flistings%2F1")]
    (testing "lands where the user was headed, signed in"
      (is (= 303 (:status response)))
      (is (= "/listings/1" (location response)))
      (is (= "alice@example.com" (:email (session-user response)))))
    (testing "the sign-in cookie is cleared"
      (is (= "" (get (set-cookies response) "jumble_login"))))))

(deftest return-to-test
  (testing "defaults to the home page"
    (is (= "/" (location (sign-in nil)))))
  (testing "only same-site paths are honored"
    (doseq [target ["%2F%2Fevil.example.com" "https%3A%2F%2Fevil.example.com"
                    "%2F%5Cevil.example.com" "evil" "%2F%09%2Fevil.example.com"]]
      (is (= "/" (location (sign-in target))) target))))

(deftest callback-csrf-test
  (let [login (request :get "/login" nil)
        callback (idp/authorize! *idp* (location login))]
    (testing "a callback without the browser's sign-in cookie is refused"
      (is (= 400 (:status (request :get callback nil)))))
    (testing "a callback carrying another browser's state is refused"
      (let [other (request :get "/login" nil)]
        (is (= 400 (:status (request :get callback (set-cookies other)))))))
    (is (= 0 (user-count)))))

(deftest error-pages-test
  (testing "sign-in failures are pages offering to sign in again"
    (doseq [response [(request :get "/auth/callback?code=x&state=forged" nil)
                      (sign-in nil {:email "bob@other.example.org"})
                      (sign-in nil {:email_verified false})]]
      (is (= "text/html; charset=utf-8" (get-in response [:headers "content-type"])))
      (is (.contains ^String (:body response) "href=\"/login\"")))))

(deftest callback-replay-test
  (let [login (request :get "/login" nil)
        callback (idp/authorize! *idp* (location login))]
    (is (= 303 (:status (request :get callback (set-cookies login)))))
    (testing "the same callback cannot sign in twice"
      (is (= 400 (:status (request :get callback (set-cookies login))))))))

(deftest callback-always-clears-sign-in-cookie-test
  (let [cleared? #(= "" (get (set-cookies %) "jumble_login"))]
    (testing "refused identity"
      (is (cleared? (sign-in nil {:email "bob@other.example.org"}))))
    (testing "unverified email"
      (is (cleared? (sign-in nil {:email_verified false}))))
    (testing "state mismatch"
      (let [login (request :get "/login" nil)]
        (is (cleared? (request :get "/auth/callback?code=x&state=forged" (set-cookies login))))))))

(deftest provider-refusal-test
  (let [login (request :get "/login" nil)
        state (get (set-cookies login) "jumble_login")]
    (is (= 403
           (:status (request :get
                             (str "/auth/callback?error=access_denied&state=" state)
                             (set-cookies login)))))))

(deftest identity-policy-test
  (testing "addresses outside the allowed domains are refused"
    (is (= 403 (:status (sign-in nil {:email "bob@other.example.org"}))))
    (is (= 403 (:status (sign-in nil {:email "bob@sub.example.com"})))))
  (testing "unverified addresses are refused"
    (is (= 403 (:status (sign-in nil {:email_verified false})))))
  (is (= 0 (user-count)))
  (testing "with hosted domains required, only accounts the email's own organisation manages get in"
    (binding [*handler* (app *idp* {:require-hosted-domain true})]
      (is (= 403 (:status (sign-in nil {}))) "a personal account registered with a work address")
      (is (= 403 (:status (sign-in nil {:hd "other.example.org"}))))
      (is (= 0 (user-count)))
      (is (= 303 (:status (sign-in nil {:hd "example.com"}))))))
  (testing "without an allowlist any verified address is accepted"
    (binding [*handler* (app *idp* {:allowed-emails :anyone})]
      (is (= 303 (:status (sign-in nil {:email "bob@other.example.org"}))))))
  (testing "with hosted domains required, addresses let in one by one need no organisation"
    (binding [*handler* (app *idp*
                             {:require-hosted-domain true
                              :allowed-emails ["*@example.com" "guest@gmail.com" "bob@example.com"]})]
      (is (= 303 (:status (sign-in nil {:email "guest@gmail.com"}))) "a personal account added by its address")
      (is (= 403 (:status (sign-in nil {:email "bob@example.com"})))
          "a work address also let in by its domain still needs the organisation")))
  (testing "single addresses can be let in besides a domain"
    (binding [*handler* (app *idp* {:allowed-emails ["*@example.com" "bob@other.example.org"]})]
      (is (= 303 (:status (sign-in nil {:email "bob@other.example.org"}))))
      (is (= 403 (:status (sign-in nil {:email "eve@other.example.org"})))))))

(deftest member-limit-test
  (binding [*handler* (app *idp* {:member-limit 1})]
    (is (= 303 (:status (sign-in nil))) "Alice, the first member")
    (testing "a new member past the limit is told the community is full"
      (let [{:keys [status body]} (sign-in nil {:sub "bob" :email "bob@example.com"})]
        (is (= 403 status))
        (is (str/includes? body "This community is full"))))
    (testing "moderators always get in"
      (is (= 303 (:status (sign-in nil {:sub "dave" :email "dave@example.com"})))))
    (is (= 2 (user-count)))))

(deftest provider-unreachable-test
  (testing "at the start of sign-in"
    (let [p (idp/start!)]
      (idp/stop! p)
      (binding [*handler* (app p nil)]
        (is (= 503 (:status (request :get "/login" nil)))))))
  (testing "between the redirect and the callback"
    (let [login (request :get "/login" nil)
          callback (idp/authorize! *idp* (location login))]
      (idp/stop! *idp*)
      (let [response (request :get callback (set-cookies login))]
        (is (= 503 (:status response)))
        (is (= "" (get (set-cookies response) "jumble_login")))))))

(deftest client-limit-test
  (let [h (app *idp* {:clients-known true})
        login (fn [addr] (:status (h {:request-method :get :uri "/login" :remote-addr addr :headers {}})))]
    (testing "one client starts a limited number of sign-ins a while, each recorded"
      (is (= 20 (count (filter #{302} (repeatedly 25 #(login "203.0.113.9"))))))
      (is (= 429 (login "203.0.113.9")))
      (is (= 20 (count (jdbc/execute! *db* ["SELECT 1 FROM login_attempt"])))))
    (testing "other clients are not held back"
      (is (= 302 (login "198.51.100.4"))))))

(deftest logout-test
  (let [signed-in (sign-in nil)
        cookies (select-keys (set-cookies signed-in) ["jumble_session"])]
    (testing "requires our origin"
      (is (= 403 (:status (request :post "/logout" cookies))))
      (is (some? (session-user signed-in))))
    (let [response (request :post "/logout" cookies :headers {"origin" base-url})]
      (is (= 303 (:status response)))
      (is (= "/" (location response)))
      (testing "ends the session server-side and clears the cookie"
        (is (nil? (session-user signed-in)))
        (is (= "" (get (set-cookies response) "jumble_session")))))))

(deftest interrupted-deletion-test
  (let [{:keys [id]} (session-user (sign-in nil))]
    ;; Deletion's first step alone, as when the rest of it failed.
    (jdbc/execute! *db* ["UPDATE user SET deleted_at = 1 WHERE id = ?" id])
    (jdbc/execute! *db* ["DELETE FROM session WHERE user_id = ?" id])
    (testing "signing in again finishes the deletion, then starts a new account"
      (let [response (sign-in nil)]
        (is (= 303 (:status response)))
        (is (not= id (:id (session-user response))))
        (is (= "deleted" (:user/iss (jdbc/execute-one! *db* ["SELECT iss FROM user WHERE id = ?" id]))))))))

(deftest removed-member-test
  (let [{:keys [email]} (session-user (sign-in nil))]
    (data/remove-member! *db* (blob/memory-store) {:email email :moderator-id nil :now 1})
    (testing "a removed member does not sign in again, though their address is let in"
      (let [response (sign-in nil)]
        (is (= 403 (:status response)))
        (is (nil? (session-user response)))))))

(deftest join-link-test
  (let [token (join/create! *db* {:moderator-id nil :expires-in nil :now 0})
        join (fn [claims]
               (let [login (request :get (str "/login?join=" token) nil)]
                 (request :get (idp/authorize! *idp* (location login) claims) (set-cookies login))))]
    (testing "the join link's page leads to signing in with the link"
      (is (str/includes? (:body (request :get (str "/join/" token) nil)) (str "/login?join=" token))))
    (testing "from the join link, an address no rule lets in joins"
      (is (= 303 (:status (join {:email "neighbour@gmail.com"}))))
      (testing "and signs in later without it"
        (is (= 303 (:status (sign-in nil {:email "neighbour@gmail.com"}))))))
    (testing "without it, refused"
      (is (= 403 (:status (sign-in nil {:email "other@gmail.com"})))))
    (testing "an address with a wildcard is refused, link or not, and lets in no one"
      (is (= 403 (:status (join {:email "*@attacker.example"}))))
      (is (= 403 (:status (sign-in nil {:email "anyone@attacker.example"})))))
    (testing "the link is enough where the provider's organisation is otherwise required"
      (binding [*handler* (app *idp* {:require-hosted-domain true})]
        (is (= 303 (:status (join {:email "friend@gmail.com"}))))
        (is ((account/allowed *db* ["*@example.com"]) "friend@gmail.com"))))
    (testing "a member the moderators removed does not come back by it"
      (data/remove-member! *db* (blob/memory-store) {:email "neighbour@gmail.com" :moderator-id nil :now 0})
      (is (= 403 (:status (join {:email "neighbour@gmail.com"})))))
    (testing "a link renewed while signing in no longer lets in"
      (let [login (request :get (str "/login?join=" token) nil)]
        (join/create! *db* {:moderator-id nil :expires-in nil :now 1})
        (is (= 403 (:status (request :get
                                     (idp/authorize! *idp* (location login) {:email "late@gmail.com"})
                                     (set-cookies login)))))))))

(deftest invitee-test
  (testing "whom a member invited signs in with the provider, organisation required or not"
    (let [ann (:id (account/upsert-user! *db* {:iss "i" :sub "ann" :email "ann@example.com" :name "Ann"} 0))]
      (doseq [address ["guest@gmail.com" "other@gmail.com"]]
        (invite/invite! *db*
                        {:member-id ann
                         :email address
                         :now 0
                         :configured ["*@example.com"]
                         :community-per-day 100
                         :email-for (fn [to] {:to to :subject "Invited" :text "Come"})}))
      (is (= 303 (:status (sign-in nil {:email "guest@gmail.com"}))))
      (binding [*handler* (app *idp* {:require-hosted-domain true})]
        (is (= 303 (:status (sign-in nil {:email "other@gmail.com"}))))))))
