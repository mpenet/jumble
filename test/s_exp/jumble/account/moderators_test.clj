(ns s-exp.jumble.account.moderators-test
  "The owner, named by the configuration, and the members the owner makes
  moderators in the app: who may do what, and the page appointing them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture request sign-in!]]
            [s-exp.jumble.store.db :as db]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(defn- appoint!
  "Signs in `email` and makes the account a moderator: its session token."
  [email]
  (let [token (sign-in! email)]
    (account/set-moderator! *db* (fixtures/user-id email) true)
    token))

(deftest roster-test
  (testing "none at first"
    (is (= [] (account/moderators *db*))))
  (testing "a member made a moderator, and no longer"
    (let [mia (do (sign-in! "mia@example.com") (fixtures/user-id "mia@example.com"))]
      (account/set-moderator! *db* mia true)
      (is (= [{:id mia :name "mia@example.com" :email "mia@example.com"}] (account/moderators *db*)))
      (account/set-moderator! *db* mia false)
      (is (= [] (account/moderators *db*)))))
  (testing "only the owner is always let in: moderators come in by the list, as members do"
    (appoint! "mia@example.com")
    (fixtures/allow-only! [])
    (is (= ["dave@example.com"] (account/allowed-emails *db* "dave@example.com")))))

(def ^:private owner-pages
  ["/manage/members" "/manage/moderators" "/manage/appearance" "/manage/poster"])

(def ^:private moderator-pages
  ["/manage" "/manage/categories" "/manage/places"])

(deftest roles-test
  (let [dave (sign-in! "dave@example.com")
        bob (sign-in! "bob@example.com")
        mia (appoint! "mia@example.com")]
    (testing "the owner manages everything"
      (doseq [page (concat owner-pages moderator-pages)]
        (is (= 200 (:status (request :get page dave))) page)))
    (testing "a moderator moderates: reports, listings, members, categories and places"
      (doseq [page moderator-pages]
        (is (= 200 (:status (request :get page mia))) page))
      (testing "but not who may sign in, moderators, appearance or the poster"
        (doseq [page owner-pages]
          (is (= 404 (:status (request :get page mia))) page))
        (is (= 404 (:status (request :post "/manage/members/anyone" mia :form {"anyone" "off"}))))
        (is (= 404 (:status (request :post "/manage/moderators" mia :form {"email" "eve@example.com"}))))
        (is (account/anyone-allowed? *db*))))
    (testing "each sees the pages they may use, linked"
      (let [nav (:body (request :get "/manage" mia))]
        (is (str/includes? nav "href=\"/manage/categories\""))
        (is (not (str/includes? nav "href=\"/manage/members\"")))
        (is (not (str/includes? nav "href=\"/manage/moderators\""))))
      (is (str/includes? (:body (request :get "/manage" dave)) "href=\"/manage/moderators\"")))
    (testing "members do neither"
      (doseq [page (concat owner-pages moderator-pages)]
        (is (= 404 (:status (request :get page bob))) page)))))

(deftest moderators-page-test
  (let [dave (sign-in! "dave@example.com")
        page #(:body (request :get "/manage/moderators" dave))]
    (testing "the owner, by the configuration, and nobody else yet"
      (is (str/includes? (page) "dave@example.com"))
      (is (re-find #"<form [^>]*method=\"post\" action=\"/manage/moderators\"" (page))))
    (testing "only a member can be appointed"
      (let [{:keys [status body]} (request :post "/manage/moderators" dave :form {"email" "mia@example.com"})]
        (is (= 422 status))
        (is (str/includes? body "No member has this address"))))
    (testing "appointing a member by address"
      (let [mia (sign-in! "mia@example.com")
            response (request :post "/manage/moderators" dave :form {"email" " Mia@Example.com "})]
        (is (= 303 (:status response)))
        (is (= "/manage/moderators" (get-in response [:headers "location"])))
        (is (= ["mia@example.com"] (mapv :email (account/moderators *db*))))
        (is (str/includes? (page) "<code>mia@example.com</code>"))
        (is (= 200 (:status (request :get "/manage" mia))))))
    (testing "every account of the mailbox, as removal goes"
      (sign-in! "mia+shop@example.com")
      (request :post "/manage/moderators" dave :form {"email" "mia@example.com"})
      (is (= ["mia+shop@example.com" "mia@example.com"] (sort (map :email (account/moderators *db*))))))
    (testing "what is not one address comes back with an explanation"
      (let [{:keys [status body]} (request :post "/manage/moderators" dave :form {"email" "*@example.com"})]
        (is (= 422 status))
        (is (str/includes? body "is not an email address"))))
    (testing "nor is the owner appointed"
      (let [{:keys [status body]} (request :post "/manage/moderators" dave :form {"email" "Dave@example.com"})]
        (is (= 422 status))
        (is (str/includes? body "the owner"))))
    (testing "withdrawn, a moderator no longer moderates, at once"
      (let [mia (sign-in! "mia@example.com")
            id (str (fixtures/user-id "mia@example.com"))]
        (is (= 303 (:status (request :post "/manage/moderators/withdraw" dave :form {"id" id}))))
        (is (= ["mia+shop@example.com"] (mapv :email (account/moderators *db*))))
        (is (= 404 (:status (request :get "/manage" mia))))
        (is (not (str/includes? (page) "<code>mia@example.com</code>")))))
    (testing "withdrawing nobody in particular changes nothing"
      (is (= 303 (:status (request :post "/manage/moderators/withdraw" dave :form {}))))
      (is (= 1 (count (account/moderators *db*)))))))

(deftest moderators-follow-the-list-test
  (let [mia (appoint! "mia@example.com")]
    (testing "limited to a list without them, a moderator is signed out, as a member is"
      (fixtures/allow-only! [])
      (is (= 302 (:status (request :get "/manage" mia)))))))

(deftest member-removal-test
  (let [mia (appoint! "mia@example.com")
        _ (sign-in! "bob@example.com")
        _ (appoint! "max@example.com")]
    (testing "moderators remove members from the reports page"
      (is (re-find #"<form [^>]*method=\"get\" action=\"/manage/members/remove\""
                   (:body (request :get "/manage" mia))))
      (is (= 200 (:status (request :get "/manage/members/remove?email=bob@example.com" mia)))))
    (testing "but not the owner, nor a moderator"
      (let [{:keys [status body]} (request :get "/manage/members/remove?email=dave@example.com" mia)]
        (is (= 422 status))
        (is (str/includes? body "the owner")))
      (let [{:keys [status body]} (request :post "/manage/members/remove" mia :form {"email" "max@example.com"})]
        (is (= 422 status))
        (is (str/includes? body "withdraw them as a moderator first"))))
    (testing "and asking with no address says so, rather than failing"
      (is (= 422 (:status (request :get "/manage/members/remove" mia))))
      (is (= 422 (:status (request :post "/manage/members/remove" mia :form {})))))
    (testing "keeping them leads back to the reports"
      (is (str/includes? (:body (request :get "/manage/members/remove?email=bob@example.com" mia))
                         "<a class=\"button secondary\" href=\"/manage\">Keep them</a>")))))

(deftest report-alerts-test
  (appoint! "mia@example.com")
  (let [ann (fixtures/user! "ann")
        lamp (fixtures/listing! ann "Lamp")]
    (request :post (str "/listings/" (:id lamp) "/report") (sign-in! "bob@example.com") :form {"reason" "Spam"})
    (testing "a report alerts the owner and the moderators"
      (is (= ["dave@example.com" "mia@example.com"]
             (mapv :recipient (jdbc/execute! *db* ["SELECT recipient FROM email_outbox ORDER BY recipient"] db/opts)))))))
