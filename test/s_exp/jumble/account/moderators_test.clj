(ns s-exp.jumble.account.moderators-test
  "The owner, named by the configuration, and the moderators the owner
  appoints in the app: who may do what, and the page appointing them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture request sign-in!]]
            [s-exp.jumble.store.db :as db]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(defn- appoint!
  [email]
  (account/appoint! *db* {:email email :owner-id nil :now 0}))

(deftest roster-test
  (testing "none at first"
    (is (= [] (account/moderators *db*))))
  (testing "appointed by address, lowercase, once"
    (appoint! "Mia@Example.com")
    (appoint! "mia@example.com")
    (is (= ["mia@example.com"] (mapv :email (account/moderators *db*))))
    (is (account/moderator? *db* "MIA@example.com")))
  (testing "always let in, as the owner is"
    (fixtures/allow-only! [])
    (is (= ["dave@example.com" "mia@example.com"] (account/allowed-emails *db* "dave@example.com"))))
  (testing "withdrawn"
    (account/withdraw-moderator! *db* "mia@example.com")
    (is (not (account/moderator? *db* "mia@example.com")))
    (is (= ["dave@example.com"] (account/allowed-emails *db* "dave@example.com")))))

(def ^:private owner-pages
  ["/manage/members" "/manage/moderators" "/manage/appearance" "/manage/poster"])

(def ^:private moderator-pages
  ["/manage" "/manage/categories" "/manage/places"])

(deftest roles-test
  (let [dave (sign-in! "dave@example.com")
        bob (sign-in! "bob@example.com")
        mia (do (appoint! "mia@example.com") (sign-in! "mia@example.com"))]
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
    (testing "appointing one by address"
      (let [response (request :post "/manage/moderators" dave :form {"email" " Mia@Example.com "})]
        (is (= 303 (:status response)))
        (is (= "/manage/moderators" (get-in response [:headers "location"]))))
      (is (account/moderator? *db* "mia@example.com"))
      (is (str/includes? (page) "<code>mia@example.com</code>"))
      (is (= 200 (:status (request :get "/manage" (sign-in! "mia@example.com"))))))
    (testing "what is not one address comes back with an explanation"
      (let [{:keys [status body]} (request :post "/manage/moderators" dave :form {"email" "*@example.com"})]
        (is (= 422 status))
        (is (str/includes? body "is not an email address"))))
    (testing "nor is the owner appointed"
      (let [{:keys [status body]} (request :post "/manage/moderators" dave :form {"email" "Dave@example.com"})]
        (is (= 422 status))
        (is (str/includes? body "the owner"))))
    (testing "withdrawn, a moderator no longer moderates, at once"
      (let [mia (sign-in! "mia@example.com")]
        (is (= 303 (:status (request :post "/manage/moderators/withdraw" dave :form {"email" "mia@example.com"}))))
        (is (not (account/moderator? *db* "mia@example.com")))
        (is (= 404 (:status (request :get "/manage" mia))))
        (is (not (str/includes? (page) "<code>mia@example.com</code>")))))))

(deftest member-removal-test
  (let [mia (do (appoint! "mia@example.com") (sign-in! "mia@example.com"))
        _ (sign-in! "bob@example.com")
        _ (do (appoint! "max@example.com") (sign-in! "max@example.com"))]
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
        (is (str/includes? body "withdraw them as a moderator first"))))))

(deftest report-alerts-test
  (appoint! "mia@example.com")
  (let [ann (fixtures/user! "ann")
        lamp (fixtures/listing! ann "Lamp")]
    (request :post (str "/listings/" (:id lamp) "/report") (sign-in! "bob@example.com") :form {"reason" "Spam"})
    (testing "a report alerts the owner and the moderators"
      (is (= ["dave@example.com" "mia@example.com"]
             (mapv :recipient (jdbc/execute! *db* ["SELECT recipient FROM email_outbox ORDER BY recipient"] db/opts)))))))
