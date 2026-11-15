(ns s-exp.jumble.web.routes-test
  "The application's handler: dispatching, and its shared middleware."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.fixtures :as fixtures]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.web.routes :as routes]))

(def echo-routes
  {[:get "/items/{id}"] (fn [request]
                          {:status 200
                           :body [(:path-params request) (:ctx request)]})})

(deftest dispatch-test
  (let [handler (routes/dispatcher echo-routes {:db ::db})]
    (testing "matched route gets path params and context"
      (is (= {:status 200 :body [{:id "42"} {:db ::db}]}
             (handler {:request-method :get :uri "/items/42"}))))
    (testing "unknown path"
      (is (= 404 (:status (handler {:request-method :get :uri "/nope"})))))
    (testing "HEAD is answered by the GET route, without a body"
      (is (= {:status 200 :body nil}
             (handler {:request-method :head :uri "/items/42"}))))
    (testing "known path, other method"
      (is (= 404 (:status (handler {:request-method :post :uri "/items/42"})))))))

(deftest signed-out-form-test
  (testing "a form sent after the session ended explains, and leads back through sign-in"
    (fixtures/db-fixture
     (fn []
       (let [{:keys [status body]} (fixtures/request :post "/listings/1/report" nil
                                                     :form {"reason" "x"}
                                                     :headers {"referer" "http://localhost:8080/listings/1"})]
         (is (= 401 status))
         (is (str/includes? body "was not saved"))
         (is (str/includes? body "href=\"/login?return_to=%2Flistings%2F1\"")))
       (testing "a Referer that is no address leads back home, not to a failure"
         (let [{:keys [status body]} (fixtures/request :post "/listings/1/report" nil
                                                       :form {"reason" "x"}
                                                       :headers {"referer" "not an address"})]
           (is (= 401 status))
           (is (str/includes? body "href=\"/login?return_to=%2F\""))))
       (testing "Datastar requests still get the bare answer the page reloads on"
         (is (= "Sign in required"
                (:body (fixtures/request :post "/listings/1/report" nil :form {"reason" "x"}
                                         :headers {"datastar-request" "true"})))))))))

(deftest error-pages-keep-the-header-test
  (testing "someone signed in keeps their header on a missing page"
    (fixtures/db-fixture
     (fn []
       (let [body (:body (fixtures/request :get "/nope" (fixtures/sign-in! "alice@example.com")))]
         (is (str/includes? body "action=\"/logout\""))
         (is (str/includes? body "href=\"/messages\"")))))))

(deftest language-test
  (fixtures/db-fixture
   (fn []
     (let [catalog (i18n/read-catalog "fr" (io/resource "i18n/xx.po"))
           token (fixtures/sign-in! "alice@example.com")
           body (:body (fixtures/request :get "/listings" token :ctx {:settings {:catalog catalog}}))]
       (testing "pages are in the instance's language"
         (is (str/includes? body "<html lang=\"fr\""))
         (is (str/includes? body "<span>Annonces</span></a>")))
       (testing "and English otherwise"
         (is (str/includes? (:body (fixtures/request :get "/listings" token)) "<html lang=\"en\"")))))))

(deftest client-address-test
  (let [seen (routes/wrap-client-address :remote-addr true)
        forwarded (fn [xff] (seen {:remote-addr "10.88.0.1" :headers {"x-forwarded-for" xff}}))]
    (testing "behind a proxy, the client is the address the proxy added: the last one"
      (is (= "203.0.113.7" (forwarded "198.51.100.1, 203.0.113.7")))
      (is (= "2001:db8::1" (forwarded " 2001:db8::1 "))))
    (testing "the proxy's own address without one, or with anything but an address"
      (is (= "10.88.0.1" (seen {:remote-addr "10.88.0.1" :headers {}})))
      (is (= "10.88.0.1" (forwarded "example.org")))
      (is (= "10.88.0.1" (forwarded "cafe.bad")) "hex letters and dots, still a name")
      (is (= "10.88.0.1" (forwarded "999.1.1.1"))))
    (testing "not behind a proxy, the header is not trusted"
      (is (= "10.88.0.1" ((routes/wrap-client-address :remote-addr false)
                          {:remote-addr "10.88.0.1" :headers {"x-forwarded-for" "203.0.113.7"}}))))))
