(ns s-exp.jumble.home.controller-test
  "The home page."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture sign-in!]]
            [s-exp.jumble.listing.model :as listing]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(defn- get-page
  [token]
  ((fixtures/handler) {:request-method :get :uri "/"
                       :headers (if token {"cookie" (str "jumble_session=" token)} {})}))

(defn- post!
  [user-id title category at & {:keys [kind] :or {kind "free"}}]
  (listing/create! *db* (blob/memory-store) user-id
                   (:listing (listing/validate fixtures/settings {:kind kind :title title :category category
                                                                  :pickup "place:hall"}))
                   [] at))

(deftest home-test
  (testing "anonymous visitors are sent to sign in"
    (let [response (get-page nil)]
      (is (= 302 (:status response)))
      (is (= "/login?return_to=%2F" (get-in response [:headers "location"])))))
  (let [token (sign-in! "alice@example.com")
        user-id (fixtures/user-id "alice@example.com")
        _ (post! user-id "Desk lamp" "home" 100)
        _ (post! user-id "Old books" "other" 200)
        given (post! user-id "Plant" "home" 300)
        _ (listing/set-status! *db* given user-id "given" 400)
        _ (post! user-id "Child's bike" "other" 50 :kind "wanted")
        {:keys [status body]} (get-page token)]
    (is (= 200 status))
    (testing "listing titles sit under the section heading"
      (is (str/includes? body "<h3 class=\"card-title\">Desk lamp</h3>")))
    (testing "a search box leading to the listings"
      (is (re-find #"<form class=\"hero-search\" method=\"get\" action=\"/listings\"" body))
      (is (re-find #"<input [^>]*name=\"q\"" body)))
    (testing "both ways in: post and browse"
      (is (str/includes? body "href=\"/listings/new\""))
      (is (str/includes? body "href=\"/listings\"")))
    (testing "one line saying how much is on offer: not what people are looking for"
      (is (re-find #"<strong>2</strong> things up for grabs" body)))
    (testing "the newest listings"
      (is (= ["Old books" "Desk lamp" "Child's bike"]
             (mapv second (re-seq #"<h3 class=\"card-title\">([^<]*)</h3>" body)))))))
