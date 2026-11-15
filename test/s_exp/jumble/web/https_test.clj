(ns s-exp.jumble.web.https-test
  "HTTPS served by the app: what its plain HTTP listener answers."
  (:require [babashka.json :as json]
            [clojure.test :refer [deftest is testing]]
            [ol.clave.acme.solver.http :as http-solver]
            [s-exp.jumble.web.https :as https]))

(set! *warn-on-reflection* true)

(deftest plain-handler-test
  (let [solver (http-solver/solver)
        app (fn [request] {:status 200 :body (str "app " (:uri request))})
        handler (https/plain-handler app solver "https://jumble.example.com")]
    (swap! (:registry solver) assoc "token" "token.thumbprint")
    (testing "the CA's challenges"
      (is (= {:status 200 :body "token.thumbprint"}
             (select-keys (handler {:request-method :get :uri "/.well-known/acme-challenge/token"}) [:status :body])))
      (is (= 404 (:status (handler {:request-method :get :uri "/.well-known/acme-challenge/other"})))))
    (testing "health checks reach the app"
      (is (= {:status 200 :body "app /healthz"} (handler {:request-method :get :uri "/healthz"}))))
    (testing "the rest goes to base-url, method and body kept"
      (is (= {:status 308 :headers {"location" "https://jumble.example.com/listings?q=lamp"}}
             (handler {:request-method :get :uri "/listings" :query-string "q=lamp"})))
      (is (= {:status 308 :headers {"location" "https://jumble.example.com/login"}}
             (handler {:request-method :post :uri "/login"}))))))

(deftest event-log-test
  (testing "certificates obtained or renewed are news"
    (is (= [:info "Certificate obtained for jumble.example.com, valid until 2027-02-10T00:00:00Z"]
           (https/event-log {:type :certificate-obtained
                             :data {:domain "jumble.example.com" :not-after (java.time.Instant/parse "2027-02-10T00:00:00Z")}})))
    (is (= :info (first (https/event-log {:type :certificate-renewed :data {:domain "jumble.example.com"}})))))
  (testing "failures need attention, with what the CA said"
    (is (= [:error "Certificate for jumble.example.com failed: rateLimited"]
           (https/event-log {:type :certificate-failed :data {:domain "jumble.example.com" :error "rateLimited"}}))))
  (testing "routine events are not logged"
    (is (nil? (https/event-log {:type :domain-added :data {:domain "jumble.example.com"}})))))

(deftest acme-json-test
  (testing "the CA's JSON is read and written with oda, as all of Jumble's"
    (is (= 'com.s-exp/oda (json/get-provider)))))
