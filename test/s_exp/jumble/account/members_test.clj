(ns s-exp.jumble.account.members-test
  "Who may sign in, as moderators manage it: patterns added and withdrawn,
  invitations, the join link, removing a member; and members inviting."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.account.data :as data]
            [s-exp.jumble.account.invite :as invite]
            [s-exp.jumble.account.join :as join]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.store.migrate :as migrate]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture sign-in!]]
            [s-exp.jumble.listing.model :as listing]))

(set! *warn-on-reflection* true)

(defn- restricted-fixture
  "Sign-in limited to the moderators and *@example.com."
  [t]
  (account/set-anyone-allowed! *db* false)
  (account/allow! *db* {:patterns ["*@example.com"] :moderator-id nil :now 0})
  (t))

(use-fixtures :each db-fixture restricted-fixture)

(def ^:private request fixtures/request)

(defn- outbox
  []
  (jdbc/execute! *db*
                 ["SELECT recipient, subject, body FROM email_outbox ORDER BY id"]
                 {:builder-fn rs/as-unqualified-maps}))

(deftest rule-test
  (testing "a new community lets in its moderators only"
    (fixtures/call-with-db-path
     (fn [path]
       (with-open [ds (db/datasource path)]
         (migrate/migrate! ds (migrate/migrations))
         (is (not (account/anyone-allowed? ds)))
         (is (= ["dave@example.com"] (account/allowed-emails ds "dave@example.com")))))))
  (testing "the moderators, then the patterns added in the app"
    (account/allow! *db* {:patterns ["guest@gmail.com" "*@partner.org"] :moderator-id nil :now 0})
    (is (= ["dave@example.com" "*@example.com" "*@partner.org" "guest@gmail.com"]
           (account/allowed-emails *db* "dave@example.com")))
    (account/allow! *db* {:patterns ["guest@gmail.com" "dave@example.com"] :moderator-id nil :now 1})
    (is (= ["dave@example.com" "*@example.com" "*@partner.org" "guest@gmail.com"]
           (account/allowed-emails *db* "dave@example.com"))
        "adding again changes nothing, a moderator added is listed once")
    (account/disallow! *db* "guest@gmail.com")
    (account/disallow! *db* "dave@example.com")
    (is (= ["dave@example.com" "*@example.com" "*@partner.org"] (account/allowed-emails *db* "dave@example.com"))))
  (testing "letting anyone in keeps the list for when it is limited again"
    (account/set-anyone-allowed! *db* true)
    (is (= :anyone (account/allowed-emails *db* "dave@example.com")))
    (account/set-anyone-allowed! *db* false)
    (is (= ["dave@example.com" "*@example.com" "*@partner.org"] (account/allowed-emails *db* "dave@example.com")))))

(deftest members-page-test
  (let [bob (sign-in! "bob@example.com")
        dave (sign-in! "dave@example.com")]
    (testing "moderators only"
      (is (= 404 (:status (request :get "/manage/members" bob))))
      (is (= 404 (:status (request :post "/manage/members" bob :form {"patterns" "eve@evil.example"}))))
      (is (= 404 (:status (request :post "/manage/members/withdraw" bob :form {"pattern" "*@example.com"}))))
      (is (str/includes? (:body (request :get "/manage" dave)) "href=\"/manage/members\"")))
    (testing "the page shows who may sign in: the list, besides the owner and moderators"
      (let [{:keys [status body]} (request :get "/manage/members" dave)]
        (is (= 200 status))
        (is (str/includes? body "href=\"/manage/moderators\""))
        (is (str/includes? body "*@example.com"))
        (is (re-find #"<form [^>]*method=\"post\" action=\"/manage/members\"" body))))
    (testing "adding addresses and patterns, inviting the addresses"
      (let [response (request :post
                              "/manage/members"
                              dave
                              :form {"patterns" "Guest@Gmail.com\n*@partner.org, other@proton.me" "invite" "on"})]
        (is (= 303 (:status response)))
        (is (= "/manage/members?added" (get-in response [:headers "location"]))))
      (let [body (:body (request :get "/manage/members?added" dave))]
        (is (str/includes? body "guest@gmail.com"))
        (is (str/includes? body "*@partner.org"))
        (is (str/includes? body "dave@example.com") "who added them"))
      (is (= ["guest@gmail.com" "other@proton.me"] (mapv :recipient (outbox))) "patterns cannot be emailed")
      (let [{:keys [subject body]} (first (outbox))]
        (is (= "You are invited to Jumble" subject))
        (is (str/includes? body "dave@example.com invited you"))
        (is (str/includes? body "http://localhost:8080/login"))))
    (testing "adding an address already there invites nobody again"
      (request :post "/manage/members" dave :form {"patterns" "guest@gmail.com" "invite" "on"})
      (is (= 2 (count (outbox)))))
    (testing "without inviting, nobody is emailed"
      (request :post "/manage/members" dave :form {"patterns" "quiet@gmail.com"})
      (is (= 2 (count (outbox)))))
    (testing "malformed patterns come back with an explanation"
      (let [{:keys [status body]} (request :post "/manage/members" dave :form {"patterns" "*@*"})]
        (is (= 422 status))
        (is (str/includes? body "Not an email pattern"))
        (is (str/includes? body "*@*") "as typed")))
    (testing "withdrawing one"
      (is (= 303 (:status (request :post "/manage/members/withdraw" dave :form {"pattern" "*@partner.org"}))))
      (is (not (str/includes? (:body (request :get "/manage/members" dave)) "<code>*@partner.org</code>"))))))

(deftest anyone-switch-test
  (let [bob (sign-in! "bob@example.com")
        dave (sign-in! "dave@example.com")
        page #(:body (request :get "/manage/members" dave))]
    (testing "moderators only"
      (is (= 404 (:status (request :post "/manage/members/anyone" bob :form {"anyone" "on"}))))
      (is (not (account/anyone-allowed? *db*))))
    (testing "limited to the list, a moderator may let anyone in"
      (is (re-find #"<form [^>]*action=\"/manage/members/anyone\"" (page)))
      (is (str/includes? (page) "Let anyone sign in"))
      (let [response (request :post "/manage/members/anyone" dave :form {"anyone" "on"})]
        (is (= 303 (:status response)))
        (is (= "/manage/members" (get-in response [:headers "location"]))))
      (is (account/anyone-allowed? *db*)))
    (testing "then there is nobody to add, and a stranger can use Jumble"
      (let [body (page)]
        (is (str/includes? body "Anyone can sign in"))
        (is (str/includes? body "Limit sign-in to the list"))
        (is (str/includes? body "<h2>Open to anyone</h2>") "laid out as the page's other sections")
        (is (not (str/includes? body "class=\"empty\"")))
        (is (not (str/includes? body "*@*.example.org")) "no patterns to explain")
        (is (not (str/includes? body "action=\"/manage/members\""))))
      (is (= 200 (:status (request :get "/listings" (sign-in! "stranger@gmail.com"))))))
    (testing "limited again, the list is as it was, and the stranger is signed out"
      (let [stranger (sign-in! "stranger@gmail.com")]
        (request :post "/manage/members/anyone" dave :form {"anyone" "off"})
        (is (not (account/anyone-allowed? *db*)))
        (is (str/includes? (page) "<code>*@example.com</code>"))
        (is (= 302 (:status (request :get "/listings" stranger))))))))

(deftest access-follows-the-list-test
  (let [dave (sign-in! "dave@example.com")
        guest (do (account/allow! *db* {:patterns ["guest@gmail.com"] :moderator-id nil :now 0})
                  (sign-in! "guest@gmail.com"))]
    (testing "an added address can use Jumble"
      (is (= 200 (:status (request :get "/listings" guest)))))
    (testing "withdrawing it signs its owner out at once, not at their next sign-in"
      (request :post "/manage/members/withdraw" dave :form {"pattern" "guest@gmail.com"})
      (is (= 302 (:status (request :get "/listings" guest))))
      (is (= 0
             (:n (jdbc/execute-one! *db*
                                    ["SELECT count(*) AS n FROM session s JOIN user u ON u.id = s.user_id
                                              WHERE u.email = 'guest@gmail.com'"]
                                    {:builder-fn rs/as-unqualified-maps})))))
    (testing "and patterns apply the same way"
      (is (= 302 (:status (request :get "/listings" (sign-in! "eve@elsewhere.org"))))))
    (testing "moderators can always sign in, the list emptied"
      (account/disallow! *db* "*@example.com")
      (is (= 200 (:status (request :get "/listings" dave)))))))

(defn- user-row
  [id]
  (jdbc/execute-one! *db*
                     ["SELECT iss, email, deleted_at FROM user WHERE id = ?" id]
                     {:builder-fn rs/as-unqualified-maps}))

(deftest remove-member-test
  (let [bob (sign-in! "bob@example.com")
        dave (sign-in! "dave@example.com")
        bob-id (fixtures/user-id "bob@example.com")
        lamp (:id (fixtures/listing! bob-id "Lamp"))]
    (testing "moderators only"
      (is (= 404 (:status (request :get "/manage/members/remove?email=dave@example.com" bob))))
      (is (= 404 (:status (request :post "/manage/members/remove" bob :form {"email" "dave@example.com"})))))
    (testing "the reports page has a form to remove someone, by email"
      (is (re-find #"<form [^>]*method=\"get\" action=\"/manage/members/remove\""
                   (:body (request :get "/manage" dave)))))
    (testing "removing asks first, saying who and what goes"
      (let [{:keys [status body]} (request :get "/manage/members/remove?email=+Bob@Example.com+" dave)]
        (is (= 200 status))
        (is (str/includes? body "bob@example.com"))
        (is (str/includes? body "1 listing"))
        (is (re-find #"<form [^>]*method=\"post\" action=\"/manage/members/remove\"" body)))
      (is (some? (user-row bob-id)) "nothing removed yet")
      (let [_ (sign-in! "carol@example.com")
            body (:body (request :get "/manage/members/remove?email=carol@example.com" dave))]
        (is (str/includes? body "and their account are deleted, and they can never sign in again")
            "of someone without listings, none named")
        (is (not (str/includes? body "No listings"))))
      (is (= "active" (:status (listing/by-id *db* lamp)))))
    (testing "only a member can be removed"
      (let [{:keys [status body]} (request :get "/manage/members/remove?email=nobody@example.com" dave)]
        (is (= 422 status))
        (is (str/includes? body "No member has this address")))
      (is (= 422 (:status (request :get "/manage/members/remove?email=not-an-address" dave)))))
    (testing "not the owner, who is one by the configuration"
      (let [{:keys [status body]} (request :get "/manage/members/remove?email=dave@example.com" dave)]
        (is (= 422 status))
        (is (str/includes? body "is the owner, by the configuration")))
      (is (= 422 (:status (request :post "/manage/members/remove" dave :form {"email" "dave@example.com"}))))
      (testing "nor by another address of their mailbox, which removal would take"
        (is (= 422 (:status (request :post "/manage/members/remove" dave :form {"email" "Dave+x@example.com"}))))
        (is (not (account/removed? *db* "dave@example.com")))
        (is (= 200 (:status (request :get "/manage/members" dave))) "still signed in, still the owner")))
    (testing "confirming deletes their account and listings"
      (let [response (request :post "/manage/members/remove" dave :form {"email" "bob@example.com"})]
        (is (= 303 (:status response)))
        (is (= "/manage?removed" (get-in response [:headers "location"]))))
      (is (= "deleted" (:iss (user-row bob-id))))
      (is (nil? (listing/by-id *db* lamp)))
      (is (= 302 (:status (request :get "/listings" bob))) "signed out"))
    (testing "and they never sign in again, whatever lets their address in"
      (is (account/removed? *db* "Bob@example.com"))
      (is (not ((account/allowed *db* "dave@example.com") "bob@example.com")))
      (account/set-anyone-allowed! *db* true)
      (is (not ((account/allowed *db* "dave@example.com") "bob@example.com"))))))

(deftest removal-covers-the-mailbox-test
  (account/set-anyone-allowed! *db* true)
  (let [old (sign-in! "carol+old@example.com")
        carol-old (fixtures/user-id "carol+old@example.com")]
    (data/remove-member! *db* (blob/memory-store) {:email "Carol@example.com" :moderator-id nil :now 1})
    (testing "a +tag variant of a removed address is the same mailbox: removed too"
      (doseq [email ["carol@example.com" "carol+1@example.com" "Carol+Tag@Example.com"]]
        (is (account/removed? *db* email) email)
        (is (not ((account/allowed *db* "dave@example.com") email)) email)))
    (testing "its accounts go with it"
      (is (= "deleted" (:iss (user-row carol-old))))
      (is (= 302 (:status (request :get "/listings" old)))))
    (testing "removing a +tag address removes its mailbox"
      (data/remove-member! *db* (blob/memory-store) {:email "dan+x@example.com" :moderator-id nil :now 2})
      (is (account/removed? *db* "dan@example.com")))
    (testing "other mailboxes are not affected"
      (is (not (account/removed? *db* "caroline@example.com")))
      (is ((account/allowed *db* "dave@example.com") "caroline@example.com")))))

(deftest join-link-page-test
  (let [bob (sign-in! "bob@example.com")
        dave (sign-in! "dave@example.com")
        page #(:body (request :get "/manage/members" dave))]
    (testing "moderators only"
      (is (= 404 (:status (request :post "/manage/members/join-link" bob :form {"expires" "never"}))))
      (is (= 404 (:status (request :post "/manage/members/join-link/off" bob))))
      (is (nil? (join/current *db* (System/currentTimeMillis)))))
    (testing "an expiry the form does not offer is refused"
      (is (= 400 (:status (request :post "/manage/members/join-link" dave :form {"expires" "1000"}))))
      (is (nil? (join/current *db* (System/currentTimeMillis)))))
    (testing "none at first: a moderator may make one, expiring or not"
      (is (re-find #"<form [^>]*action=\"/manage/members/join-link\"" (page)))
      (is (str/includes? (page) "name=\"expires\""))
      (let [response (request :post "/manage/members/join-link" dave :form {"expires" "30"})]
        (is (= 303 (:status response)))
        (is (= "/manage/members" (get-in response [:headers "location"])))))
    (let [{:keys [token expires-at created-at]} (join/current *db* (System/currentTimeMillis))]
      (testing "then the page shows it whole, to copy or print, with who made it and until when"
        (is (some? token))
        (is (= (* 30 24 60 60 1000) (- expires-at created-at)))
        (is (str/includes? (page) (str "http://localhost:8080/join/" token)))
        (is (str/includes? (page) "Made by dave@example.com")))
      (testing "renewed, the earlier link no longer works; never expiring by default"
        (request :post "/manage/members/join-link" dave :form {"expires" "never"})
        (let [renewed (join/current *db* (System/currentTimeMillis))]
          (is (not= token (:token renewed)))
          (is (nil? (:expires-at renewed)))
          (is (not (str/includes? (page) token))))))
    (testing "who joined by it is shown as such"
      (account/email-user! *db* "neighbour@gmail.com" 0 {:joining true})
      (is (str/includes? (page) "neighbour@gmail.com"))
      (is (str/includes? (page) "joined with the join link")))
    (testing "turned off, it is gone"
      (request :post "/manage/members/join-link/off" dave)
      (is (nil? (join/current *db* (System/currentTimeMillis))))
      (is (not (str/includes? (page) "/join/"))))
    (testing "nothing to offer where anyone may sign in"
      (account/set-anyone-allowed! *db* true)
      (is (not (re-find #"action=\"/manage/members/join-link\"" (page)))))))

(deftest join-link-poster-test
  (let [dave (sign-in! "dave@example.com")
        poster #(:body (request :get % dave))
        code #(re-find #"(?s)<svg class=\"qr\".*?</svg>" (poster %))]
    (testing "without a join link, the code opens the community, with no choice to make"
      (is (not (str/includes? (poster "/manage/poster") "code=join")))
      (is (= (code "/manage/poster") (code "/manage/poster?code=join"))))
    (join/create! *db* {:moderator-id (fixtures/user-id "dave@example.com") :now (System/currentTimeMillis)})
    (testing "with one, moderators choose what the code opens: the community unless asked"
      (is (str/includes? (poster "/manage/poster") "href=\"/manage/poster?code=join\""))
      (is (str/includes? (poster "/manage/poster") "Scan to open Jumble"))
      (is (not= (code "/manage/poster") (code "/manage/poster?code=join")))
      (is (str/includes? (poster "/manage/poster?code=join") "Scan to join Jumble")))
    (testing "each choice holds as the other changes"
      (is (str/includes? (poster "/manage/poster?code=join") "href=\"/manage/poster?code=join&amp;language=fr\""))
      (is (str/includes? (poster "/manage/poster?language=fr") "href=\"/manage/poster?code=join&amp;language=fr\""))
      (is (str/includes? (poster "/manage/poster?code=join&language=xx") "href=\"/manage/poster\"")
          "a language Jumble does not speak is not carried along"))
    (testing "nothing to choose where anyone may sign in"
      (account/set-anyone-allowed! *db* true)
      (is (not (str/includes? (poster "/manage/poster") "code=join"))))))

(deftest member-invitations-switch-test
  (let [bob (sign-in! "bob@example.com")
        dave (sign-in! "dave@example.com")
        page #(:body (request :get "/manage/members" dave))]
    (testing "moderators only"
      (is (= 404 (:status (request :post "/manage/members/invitations" bob :form {"open" "on"}))))
      (is (not (invite/open? *db*))))
    (testing "closed at first: a moderator may let members invite"
      (is (re-find #"<form [^>]*action=\"/manage/members/invitations\"" (page)))
      (is (str/includes? (page) "Let members invite people"))
      (let [response (request :post "/manage/members/invitations" dave :form {"open" "on"})]
        (is (= 303 (:status response)))
        (is (= "/manage/members" (get-in response [:headers "location"]))))
      (is (invite/open? *db*))
      (is (str/includes? (page) "Stop members inviting people")))
    (testing "whom a member invited is shown as such"
      (invite/invite! *db*
                      {:member-id (fixtures/user-id "bob@example.com")
                       :email "neighbour@gmail.com"
                       :now 0
                       :owner "dave@example.com"
                       :community-per-day 100
                       :email-for (fn [to] {:to to :subject "Invited" :text "Come"})})
      (is (str/includes? (page) "invited by bob@example.com")))
    (testing "and closed again"
      (request :post "/manage/members/invitations" dave :form {"open" "off"})
      (is (not (invite/open? *db*))))
    (testing "nothing to offer where anyone may sign in"
      (account/set-anyone-allowed! *db* true)
      (is (not (re-find #"action=\"/manage/members/invitations\"" (page)))))))

(deftest invite-page-test
  (let [bob (sign-in! "bob@example.com")
        page #(:body (request :get % bob))
        invite! #(request :post "/invite" bob :form {"email" %})]
    (testing "closed, saying so, while moderators have not let members invite"
      (let [{:keys [status body]} (request :get "/invite" bob)]
        (is (= 403 status))
        (is (str/includes? body "Invitations are closed"))
        (is (str/includes? body "href=\"/settings\"")))
      (is (= 403 (:status (invite! "neighbour@gmail.com"))))
      (is (= 403 (:status (request :get "/invite" (sign-in! "dave@example.com")))) "moderators too")
      (is (= [] (outbox)))
      (is (not (str/includes? (page "/settings") "href=\"/invite\""))))
    (invite/set-open! *db* true)
    (testing "then members' settings lead to it"
      (is (str/includes? (page "/settings") "href=\"/invite\""))
      (is (re-find #"<form [^>]*action=\"/invite\"" (page "/invite")))
      (is (str/includes? (page "/invite") "The email gives your name, bob@example.com.")))
    (testing "an address that is not one comes back with an explanation"
      (let [{:keys [status body]} (invite! "nope")]
        (is (= 422 status))
        (is (str/includes? body "is not an email address"))))
    (testing "nor is a pattern, which would let in a whole domain"
      (is (= 422 (:status (invite! "*@gmail.com"))))
      (is (not ((account/allowed *db* "dave@example.com") "stranger@gmail.com"))))
    (testing "inviting emails the address, from the member, and lists it"
      (let [response (invite! "Neighbour@Gmail.com")]
        (is (= 303 (:status response)))
        (is (= "/invite?sent" (get-in response [:headers "location"]))))
      (let [[{:keys [recipient body]}] (outbox)]
        (is (= "neighbour@gmail.com" recipient))
        (is (str/includes? body "bob@example.com invited you"))
        (is (str/includes? body "If you do not know bob@example.com, you can ignore this email.")))
      (let [body (page "/invite?sent")]
        (is (str/includes? body "If the address can join, an invitation is on its way."))
        (is (str/includes? body "neighbour@gmail.com"))))
    (testing "past five a day, the member is asked to wait"
      (doseq [i (range 4)]
        (invite! (str "n" i "@gmail.com")))
      (let [{:keys [status body]} (invite! "sixth@gmail.com")]
        (is (= 429 status))
        (is (str/includes? body "You have sent 5 invitations in the last 24 hours"))))
    (testing "past what members may send in all, they are asked to wait"
      (let [{:keys [status body]} (fixtures/request :post
                                                    "/invite"
                                                    (sign-in! "carl@example.com")
                                                    {:ctx {:settings {:invitations-per-day 5}}
                                                     :form {"email" "seventh@gmail.com"}})]
        (is (= 429 status))
        (is (str/includes? body "Members have sent as many invitations as they may"))))
    (testing "nothing where anyone may sign in"
      (account/set-anyone-allowed! *db* true)
      (is (= 404 (:status (request :get "/invite" bob))))
      (is (not (str/includes? (page "/settings") "href=\"/invite\""))))))

(deftest provider-note-test
  (let [dave (sign-in! "dave@example.com")
        page #(:body (fixtures/request :get "/manage/members" dave {:ctx {:settings {:sign-in %}}}))]
    (testing "with a sign-in provider, moderators are told joiners need an account there"
      (is (str/includes? (page "oidc") "need an account at your sign-in provider")))
    (testing "not with email sign-in"
      (is (not (str/includes? (page "email") "need an account at your sign-in provider"))))))
