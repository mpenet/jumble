(ns s-exp.jumble.page.theme-test
  "Light and dark themes: choosing one, and pages rendered in it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [s-exp.jumble.fixtures :as fixtures :refer [db-fixture sign-in!]]
            [s-exp.jumble.view.layout :as layout])
  (:import (java.io ByteArrayInputStream)))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(defn- post-theme
  [body & {:keys [headers]}]
  ((fixtures/handler) {:request-method :post :uri "/theme"
                       :headers (merge {"origin" "http://localhost:8080"
                                        "content-type" "application/x-www-form-urlencoded"}
                                       headers)
                       :body (ByteArrayInputStream. (.getBytes ^String body))}))

(defn- theme-cookie
  [response]
  (some #(second (re-find #"^jumble_theme=([^;]*)" %)) (get-in response [:headers "Set-Cookie"])))

(deftest choose-theme-test
  (testing "the choice is remembered and the browser goes back where it was"
    (let [response (post-theme "theme=dark&return_to=%2Flistings%3Fkind%3Dfree")]
      (is (= 303 (:status response)))
      (is (= "/listings?kind=free" (get-in response [:headers "location"])))
      (is (= "dark" (theme-cookie response)))))
  (testing "unknown themes are ignored"
    (is (nil? (theme-cookie (post-theme "theme=neon&return_to=%2F")))))
  (testing "only paths on this site to go back to"
    (is (= "/" (get-in (post-theme "theme=light&return_to=%2F%2Fevil.example.com") [:headers "location"]))))
  (testing "and only ones that fit in a header: no characters beyond ASCII"
    (is (= "/" (get-in (post-theme "theme=light&return_to=%2F%E2%80%A8") [:headers "location"]))))
  (testing "with Datastar the page already switched: just remember it"
    (let [response (post-theme "theme=light" :headers {"datastar-request" "true"})]
      (is (= 204 (:status response)))
      (is (= "light" (theme-cookie response))))))

(deftest rendered-theme-test
  (let [page #(layout/page {:csp-nonce "n" :cookies %1 :ctx {:settings {:app-name "Jumble"}}} {} [:p "x"])]
    (testing "the chosen theme is on the page from the start: no flash"
      (is (str/starts-with? (page {"jumble_theme" {:value "dark"}})
                            "<!DOCTYPE html><html lang=\"en\" data-theme=\"dark\"")))
    (testing "without a choice (or a bogus one) the system preference applies"
      (is (not (str/includes? (page {}) "data-theme")))
      (is (not (str/includes? (page {"jumble_theme" {:value "x\"><script>"}}) "data-theme"))))))

(deftest toggle-in-header-test
  (let [body (:body ((fixtures/handler) {:request-method :get :uri "/listings" :query-string "kind=free"
                                         :headers {"cookie" (str "jumble_session="
                                                                 (sign-in! "a@example.com")
                                                                 "; jumble_theme=dark")}}))]
    (is (re-find #"<form class=\"theme-toggle\" method=\"post\" action=\"/theme\"" body))
    (testing "without JS it switches to the other theme and comes back here"
      (is (str/includes? body "<input type=\"hidden\" name=\"theme\" value=\"light\">"))
      (is (str/includes? body "<input type=\"hidden\" name=\"return_to\" value=\"/listings?kind=free\">")))))
