(ns s-exp.jumble.web.auth-test
  "Who is signed in: routes for members only, renewed sessions, cookies, the
  same-origin check."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.web.auth :as auth]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def ttl fixtures/session-ttl)

(defn- ctx
  [base-url]
  {:db *db* :settings {:base-url base-url :session-ttl ttl}})

(def echo (auth/require-user (fn [request] {:status 200 :body (:user request)})))

(defn- request
  [method uri & {:keys [cookie headers query-string]}]
  {:request-method method
   :uri uri
   :query-string query-string
   :headers (or headers {})
   :cookies (if cookie {"jumble_session" {:value cookie}} {})
   :ctx (ctx "http://localhost:8080")})

(deftest require-user-test
  (let [user (account/upsert-user! *db*
                                   {:iss "i" :sub "s" :email "a@example.com" :name "A"}
                                   (System/currentTimeMillis))
        t (account/create-session! *db* (:id user) (System/currentTimeMillis) ttl)]
    (testing "a live session cookie resolves to its user"
      (is (= {:status 200 :body (assoc user :language nil)} (echo (request :get "/listings" :cookie t)))))
    (testing "anonymous page loads go to login, remembering where they were headed"
      (is (= {:status 302 :headers {"location" "/login?return_to=%2Flistings%3Fq%3Dlamp"} :body ""}
             (echo (request :get "/listings" :query-string "q=lamp")))))
    (testing "an unknown token counts as anonymous"
      (is (= 302 (:status (echo (request :get "/listings" :cookie "forged"))))))
    (testing "anonymous writes and Datastar requests get 401, not a redirect"
      (is (= 401 (:status (echo (request :post "/listings")))))
      (is (= 401 (:status (echo (request :get "/listings" :headers {"datastar-request" "true"}))))))))

(deftest renewed-session-reissues-cookie-test
  (let [day (* 24 60 60 1000)
        user (account/upsert-user! *db* {:iss "i" :sub "s" :email "a@example.com" :name "A"} 0)
        now (System/currentTimeMillis)
        fresh (account/create-session! *db* (:id user) now ttl)
        aging (account/create-session! *db* (:id user) (- now (* 3 day)) ttl)]
    (testing "a session renewed server-side gets a cookie lasting as long"
      (is (= {"jumble_session" {:value aging :path "/" :http-only true :secure false
                                :same-site :lax :max-age (quot ttl 1000)}}
             (:cookies (echo (request :get "/listings" :cookie aging))))))
    (testing "an unrenewed session leaves the cookie alone"
      (is (nil? (:cookies (echo (request :get "/listings" :cookie fresh))))))
    (testing "cookies the handler sets win"
      (let [aging (account/create-session! *db* (:id user) (- now (* 3 day)) ttl)
            handler (auth/require-user (fn [_] {:status 200 :cookies {"jumble_session" {:value ""}}}))]
        (is (= {:value ""} (get-in (handler (request :get "/" :cookie aging)) [:cookies "jumble_session"])))))))

(deftest same-origin-test
  (let [handler (auth/wrap-same-origin (constantly {:status 200}) "https://jumble.example.com")]
    (testing "safe methods pass without an Origin"
      (is (= 200 (:status (handler {:request-method :get :headers {}}))))
      (is (= 200 (:status (handler {:request-method :head :headers {}})))))
    (testing "unsafe methods need our exact origin"
      (is (= 200 (:status (handler {:request-method :post :headers {"origin" "https://jumble.example.com"}}))))
      (is (= 403 (:status (handler {:request-method :post :headers {"origin" "https://evil.example.com"}}))))
      (is (= 403 (:status (handler {:request-method :post :headers {"origin" "http://jumble.example.com"}}))))
      (is (= 403 (:status (handler {:request-method :delete :headers {"origin" "null"}}))))
      (is (= 403 (:status (handler {:request-method :post :headers {}})))))))

(deftest cookie-test
  (testing "over https: __Host- prefix and Secure"
    (is (= "__Host-jumble_session" (auth/cookie-name "https://jumble.example.com" "jumble_session")))
    (is (= {:value "v" :path "/" :http-only true :secure true :same-site :lax :max-age 60}
           (auth/cookie "https://jumble.example.com" "v" 60))))
  (testing "over plain http for local development: no prefix, not Secure"
    (is (= "jumble_session" (auth/cookie-name "http://localhost:8080" "jumble_session")))
    (is (false? (:secure (auth/cookie "http://localhost:8080" "v" 60))))))
