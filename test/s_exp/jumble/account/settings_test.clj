(ns s-exp.jumble.account.settings-test
  "A member's settings page: their name, and their data."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture request sign-in!]]
            [s-exp.jumble.listing.model]
            [s-exp.jumble.message.model]
            [s-exp.jumble.view.i18n :as i18n]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def ^:private checkbox
  #"<input [^>]*name=\"notify_messages\"[^>]*>")

(deftest settings-test
  (let [token (sign-in! "alice@example.com")
        alice (fixtures/user-id "alice@example.com")]
    (testing "anonymous visitors are sent to sign in"
      (is (= 302 (:status (request :get "/settings" nil)))))
    (testing "the menu under the member's name leads to the settings"
      (is (re-find #"(?s)<details class=\"account-menu\".*?<a href=\"/settings\"" (:body (request :get "/listings" token)))))
    (testing "message emails are on by default"
      (let [{:keys [status body]} (request :get "/settings" token)]
        (is (= 200 status))
        (is (str/includes? (re-find checkbox body) "checked"))))
    (testing "turning them off: an unchecked box is not sent"
      (let [response (request :post "/settings" token :form {})]
        (is (= 303 (:status response)))
        (is (= "/settings?saved" (get-in response [:headers "location"]))))
      (is (false? (account/notify-messages *db* alice)))
      (let [body (:body (request :get "/settings" token))]
        (is (not (str/includes? (re-find checkbox body) "checked")))))
    (testing "and on again"
      (request :post "/settings" token :form {"notify_messages" "on"})
      (is (true? (account/notify-messages *db* alice))))
    (testing "saving says so"
      (is (str/includes? (:body (request :get "/settings?saved" token)) "Saved.")))))

(deftest weekly-digest-test
  (let [token (sign-in! "carol@example.com")
        carol (fixtures/user-id "carol@example.com")]
    (testing "the weekly email of what is new is on by default"
      (is (re-find #"<input [^>]*name=\"weekly_digest\"[^>]*checked" (:body (request :get "/settings" token)))))
    (testing "off when unchecked, on again when checked"
      (request :post "/settings" token :form {"notify_messages" "on"})
      (is (false? (account/weekly-digest *db* carol)))
      (request :post "/settings" token :form {"notify_messages" "on" "weekly_digest" "on"})
      (is (true? (account/weekly-digest *db* carol))))))

(deftest language-test
  (let [token (sign-in! "alice@example.com")
        lang-of #(second (re-find #"<html lang=\"([a-z]+)\"" (:body %)))]
    (testing "the community's language until a member chooses theirs"
      (is (= "en" (lang-of (request :get "/listings" token))))
      (is (re-find #"<option value=\"\" selected>" (:body (request :get "/settings" token)))))
    (testing "a member's own: their pages in it"
      (request :post "/settings" token :form {"notify_messages" "on" "language" "fr"})
      (let [response (request :get "/listings" token)]
        (is (= "fr" (lang-of response)))
        (is (str/includes? (:body response) "<span>Annonces</span>")))
      (is (re-find #"<option value=\"fr\" selected>" (:body (request :get "/settings" token)))))
    (testing "pages any route can end on too: not found"
      (let [response (request :get "/no/such/page" token)]
        (is (= 404 (:status response)))
        (is (= "fr" (lang-of response)))))
    (testing "back to the community's"
      (request :post "/settings" token :form {"notify_messages" "on" "language" ""})
      (is (= "en" (lang-of (request :get "/listings" token)))))
    (testing "a language Jumble does not speak is the community's"
      (request :post "/settings" token :form {"notify_messages" "on" "language" "xx"})
      (is (= "en" (lang-of (request :get "/listings" token)))))
    (testing "English chosen in a French community"
      (let [french {:settings {:catalog (i18n/catalog "fr")}}]
        (is (= "fr" (lang-of (request :get "/listings" token :ctx french))))
        (request :post "/settings" token :form {"notify_messages" "on" "language" "en"} :ctx french)
        (is (= "en" (lang-of (request :get "/listings" token :ctx french))))))))

(def ^:private email-mode
  {:settings {:sign-in "email" :allowed-emails ["*@example.com"]}})

(defn- email-user!
  "Signs `email` in as email sign-in does; returns a session token."
  [email]
  (let [user (account/email-user! *db* email 0)]
    (account/create-session! *db* (:id user) (System/currentTimeMillis) fixtures/session-ttl)))

(deftest display-name-test
  (let [token (email-user! "alice@example.com")
        page #(request :get "/settings" token :ctx email-mode)
        save #(request :post "/settings" token :ctx email-mode :form %)]
    (testing "people signing in by email start as their address, and can choose a name"
      (is (re-find #"<input [^>]*name=\"name\"[^>]*value=\"alice@example.com\"" (:body (page))))
      (is (= 303 (:status (save {"name" "  Alice Martin " "notify_messages" "on"}))))
      (is (= "Alice Martin" (:name (account/email-user! *db* "alice@example.com" 1))) "signing in again keeps it")
      (is (str/includes? (:body (request :get "/listings" token :ctx email-mode)) "<span class=\"user-name-text\">Alice Martin</span>")))
    (testing "a name is required, and not too long"
      (let [{:keys [status body]} (save {"name" " "})]
        (is (= 422 status))
        (is (str/includes? body "Enter your name.")))
      (is (= 422 (:status (save {"name" (apply str (repeat 81 "x"))}))))
      (is (= "Alice Martin" (:name (account/email-user! *db* "alice@example.com" 2))))))
  (testing "with a sign-in provider, the name comes from it: no field, and none taken"
    (let [token (sign-in! "bob@example.com")
          bob (fixtures/user-id "bob@example.com")]
      (is (not (str/includes? (:body (request :get "/settings" token)) "name=\"name\"")))
      (request :post "/settings" token :form {"name" "Robert"})
      (is (= "bob@example.com" (:name (jdbc/execute-one! *db* ["SELECT name FROM user WHERE id = ?" bob]
                                                         {:builder-fn rs/as-unqualified-maps})))))))

(deftest your-data-test
  (let [token (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        alice (fixtures/user-id "alice@example.com")
        lamp (:id (fixtures/listing! alice "Lamp"))
        thread (s-exp.jumble.message.model/start! *db* {:sender-id (fixtures/user-id "bob@example.com")
                                                        :listing-id lamp :subject "Lamp" :body "Still there?"
                                                        :now 1})]
    (testing "the settings lead to both"
      (let [body (:body (request :get "/settings" token))]
        (is (str/includes? body "href=\"/settings/export\""))
        (is (str/includes? body "href=\"/settings/delete\""))))
    (testing "the export is a JSON file to download"
      (let [{:keys [status headers body]} (request :get "/settings/export" token)]
        (is (= 200 status))
        (is (str/starts-with? (get headers "content-type") "application/json"))
        (is (re-find #"attachment; filename=\"jumble-data-\d{4}-\d{2}-\d{2}\.json\"" (get headers "content-disposition")))
        (is (str/includes? body "\"title\":\"Lamp\""))
        (is (str/includes? body "Still there?"))))
    (testing "deleting asks first"
      (let [{:keys [status body]} (request :get "/settings/delete" token)]
        (is (= 200 status))
        (is (str/includes? body "action=\"/settings/delete\""))))
    (testing "deleting signs out and says so"
      (let [{:keys [status body headers]} (request :post "/settings/delete" token :form {})]
        (is (= 200 status))
        (is (str/includes? body "Your account was deleted"))
        (is (some #(str/includes? % "Max-Age=0") (get headers "Set-Cookie" [])))))
    (testing "the account is gone"
      (is (= 302 (:status (request :get "/settings" token))))
      (is (nil? (s-exp.jumble.listing.model/by-id *db* lamp))))
    (testing "the other side sees a former member, and cannot reply"
      (let [body (:body (request :get (str "/messages/" thread) bob))]
        (is (str/includes? body "A former member"))
        (is (str/includes? body "deleted their account"))
        (is (not (str/includes? body "class=\"reply-form\"")))))))
