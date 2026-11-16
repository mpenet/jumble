(ns s-exp.jumble.page.privacy-test
  "The privacy page."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [s-exp.jumble.fixtures :as fixtures :refer [db-fixture request sign-in!]]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(deftest privacy-page-test
  (testing "anyone can read it, signed in or not"
    (let [{:keys [status body]} (request :get "/privacy" nil)]
      (is (= 200 status))
      (is (str/includes? body "<h1>Privacy</h1>"))
      (testing "the default text says what is kept, the cookies, and how to leave"
        (is (str/includes? body "no tracking"))
        (is (str/includes? body "a weekly summary of what is new, which you can turn off"))
        (is (str/includes? body "keeps you signed in"))
        (is (str/includes? body "href=\"/settings\""))
        (is (str/includes? body "Ask your community's moderators.")))))
  (testing "a contact address, when set, is where questions go"
    (is (str/includes? (:body (request :get "/privacy" nil :ctx {:settings {:contact "privacy@club.org"}}))
                       "<a href=\"mailto:privacy@club.org\">privacy@club.org</a>")))
  (testing "new posts announced in a chat channel, when they are, say so"
    (is (not (str/includes? (:body (request :get "/privacy" nil)) "chat channel")))
    (is (str/includes? (:body (request :get "/privacy" nil :ctx {:settings {:chat-webhook-url "https://hooks.example.org/x"}}))
                       "New listings are also announced in your community's chat channel"))
    (is (str/includes? (:body (request :get "/privacy" nil :ctx {:settings {:irc {:channel "#swap"}}}))
                       "New listings are also announced in your community's chat channel")))
  (testing "the community's own text replaces it"
    (let [body (:body (request :get "/privacy" nil :ctx {:settings {:privacy "# Our rules\n\nWe keep **little**."}}))]
      (is (str/includes? body "<strong>little</strong>"))
      (is (not (str/includes? body "no tracking")))))
  (testing "every page links to it"
    (is (str/includes? (:body (request :get "/listings" (sign-in! "alice@example.com"))) "href=\"/privacy\""))
    (is (str/includes? (:body (request :get "/privacy" nil)) "href=\"/privacy\""))))
