(ns s-exp.jumble.app-test
  "Behaviour of the assembled application handler, across contexts."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [s-exp.jumble.page.assets :as assets]
            [s-exp.jumble.fixtures :refer [db-fixture handler sign-in!]]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(defn- get-page
  [uri]
  ((handler) {:request-method :get :uri uri :headers {}}))

(deftest not-found-test
  (let [{:keys [status headers ^String body]} (get-page "/nope")]
    (is (= 404 status))
    (is (= "text/html; charset=utf-8" (get headers "content-type")))
    (is (.contains body "<h1>Not found</h1>"))
    (is (some? (get headers "content-security-policy")))))

(deftest assets-through-handler-test
  (let [{:keys [status headers]} (get-page (assets/url "app.css"))]
    (is (= 200 status))
    (is (= "public, max-age=31536000, immutable" (get headers "cache-control")))
    (is (= "nosniff" (get headers "x-content-type-options")))))

(defn- post-form
  [body headers]
  ((handler) {:request-method :post :uri "/theme"
              :headers (merge {"origin" "http://localhost:8080"
                               "content-type" "application/x-www-form-urlencoded"}
                              headers)
              :body (java.io.ByteArrayInputStream. ^bytes body)}))

(deftest body-limit-test
  (let [big (.getBytes (str "theme=dark&x=" (apply str (repeat (* 2 1024 1024) "a"))))]
    (testing "form bodies over 1 MB are refused before being read into memory"
      (is (= 413 (:status (post-form big {"content-length" (str (alength big))})))))
    (testing "also when the size is not announced"
      (is (= 413 (:status (post-form big {})))))
    (testing "normal forms pass"
      (is (= 303 (:status (post-form (.getBytes "theme=dark&return_to=%2F") {})))))))

(deftest error-page-header-test
  (testing "an error page keeps the signed-in header"
    (let [token (sign-in! "alice@example.com")
          big (.getBytes ^String (apply str (repeat (* 2 1024 1024) "a")))
          {:keys [status ^String body]} (post-form big
                                                   {"content-length" (str (alength big))
                                                    "cookie" (str "jumble_session=" token)})]
      (is (= 413 status))
      (is (.contains body "Sign out")))))
