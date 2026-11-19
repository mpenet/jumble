(ns s-exp.jumble.email.language-test
  "Emails in their recipient's language: the one a member chose, the
  community's otherwise, whoever's request or work wrote them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture request sign-in!]]
            [s-exp.jumble.listing.freshness :as freshness]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.listing.saved-searches :as saved]
            [s-exp.jumble.message.model :as message]
            [s-exp.jumble.message.notifier :as notifier]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.view.i18n :as i18n]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def ^:private day (* 24 60 60 1000))

(defn- subject-to
  [email]
  (some #(when (= email (:recipient %)) (:subject %))
        (jdbc/execute! *db*
                       ["SELECT recipient, subject FROM email_outbox ORDER BY id"]
                       {:builder-fn rs/as-unqualified-maps})))

(defn- in
  "`text` as it reads in `language`."
  ([language text] (in language text nil))
  ([language text values]
   (binding [i18n/*catalog* (i18n/catalog language)]
     (i18n/t text values))))

(defn- speaks!
  [email language]
  (account/set-language! *db* (fixtures/user-id email) language))

(deftest report-and-removal-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        dave (sign-in! "dave@example.com")
        id (fixtures/listing! (fixtures/user-id "alice@example.com") "Desk lamp")]
    (speaks! "dave@example.com" "fr")
    (speaks! "bob@example.com" "de")
    (testing "a report alerts each moderator in theirs, not the reporter's"
      (request :post (str "/listings/" (:id id) "/report") bob :form {"reason" "x"})
      (is (= (in "fr" "Listing reported: {title}" {:title "Desk lamp"}) (subject-to "dave@example.com"))))
    (testing "a removal tells the owner in theirs, not the moderator's"
      (speaks! "alice@example.com" "de")
      (request :post (str "/manage/listings/" (:id id) "/remove") dave :form {"reason" "Not allowed"})
      (is (= (in "de" "Your listing was removed: {title}" {:title "Desk lamp"}) (subject-to "alice@example.com"))))
    (is (some? alice))))

(deftest invitation-test
  (testing "an invitation, to someone not a member yet, is in the community's language"
    (let [dave (sign-in! "dave@example.com")]
      (speaks! "dave@example.com" "fr")
      (fixtures/allow-only! ["*@example.com"])
      (request :post "/manage/members" dave :form {"patterns" "zoe@gmail.com" "invite" "on"})
      (is (= "You are invited to Jumble" (subject-to "zoe@gmail.com"))))))

(def ^:private email-mode
  {:settings {:sign-in "email"}})

(deftest sign-in-link-test
  (sign-in! "alice@example.com")
  (speaks! "alice@example.com" "fr")
  (testing "a member's sign-in link is in their language"
    (request :post "/login" nil :form {"email" "alice@example.com"} :ctx email-mode)
    (is (= (in "fr" "Sign in to {app-name}" {:app-name "Jumble"}) (subject-to "alice@example.com"))))
  (testing "an address without an account gets the community's"
    (request :post "/login" nil :form {"email" "new@example.com"} :ctx email-mode)
    (is (= "Sign in to Jumble" (subject-to "new@example.com")))))

(deftest background-emails-test
  (let [alice (fixtures/user-id (do (sign-in! "alice@example.com") "alice@example.com"))
        bob (fixtures/user-id (do (sign-in! "bob@example.com") "bob@example.com"))
        lamp (listing/create! *db*
                              (blob/memory-store)
                              alice
                              (:listing (listing/validate fixtures/settings
                                                          {:kind "free" :title "Lamp" :category "home"
                                                           :pickup "place:hall"}))
                              []
                              0)]
    (speaks! "alice@example.com" "fr")
    (testing "message emails, sent by the background work, in the recipient's"
      (message/start! *db* {:sender-id bob :listing-id lamp :subject "Lamp" :body "Still there?" :now 0})
      (let [emails (atom [])]
        (notifier/deliver-due! *db* #(swap! emails conj %) fixtures/settings (* 60 60 1000) (constantly true))
        (is (= (binding [i18n/*catalog* (i18n/catalog "fr")]
                 (i18n/tn "New message from {name}: {subject}"
                          "New messages from {name}: {subject}"
                          1
                          {:name "bob@example.com" :subject "Lamp"}))
               (:subject (first @emails))))))
    (testing "a listing's owner asked about it in theirs"
      (freshness/sweep! *db* fixtures/settings (* 31 day))
      (is (= (in "fr" "Is \"{title}\" still available?" {:title "Lamp"}) (subject-to "alice@example.com"))))
    (testing "saved searches' news in the searcher's"
      (speaks! "bob@example.com" "de")
      (saved/save! *db* bob {:q "chair" :kind nil :category nil} 0)
      (listing/create! *db*
                       (blob/memory-store)
                       alice
                       (:listing (listing/validate fixtures/settings
                                                   {:kind "free" :title "Chair" :category "home"
                                                    :pickup "place:hall"}))
                       []
                       day)
      (saved/send-due! *db* fixtures/settings (* 3 day))
      (is (= (binding [i18n/*catalog* (i18n/catalog "de")]
               (i18n/tn "New on {app-name}: {n} listing for your saved searches"
                        "New on {app-name}: {n} listings for your saved searches"
                        1
                        {:app-name "Jumble"}))
             (subject-to "bob@example.com"))))))
