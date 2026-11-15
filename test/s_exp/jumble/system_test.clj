(ns s-exp.jumble.system-test
  "The whole system started from a configuration, serving requests."
  (:require [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [s-exp.enso :as enso]
            [s-exp.jumble :as jumble]
            [s-exp.jumble.fixtures :refer [call-with-db-path]]
            [s-exp.jumble.store.migrate :as migrate])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)))

(set! *warn-on-reflection* true)

(defn- http-get
  [port path]
  (let [request (.build (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port path))))
        ^HttpResponse response (with-open [client (HttpClient/newHttpClient)]
                                 (.send client request (HttpResponse$BodyHandlers/ofString)))]
    {:status (.statusCode response)
     :body (.body response)}))

(defn- config
  [db-path]
  {:http {:host "127.0.0.1" :port 0}
   :db {:path db-path}
   :base-url "http://localhost"
   :currency "CHF"
   :app-name "Jumble"
   :moderators #{"dave@example.com"}
   :storage {:endpoint "http://127.0.0.1:1" :region "r" :bucket "b" :access-key "a" :secret-key "s"}
   ;; Discovery is lazy: nothing listens here and the system still starts.
   :auth {:issuer "http://127.0.0.1:1/dex" :client-id "jumble" :client-secret "s"
          :method "oidc" :allowed-emails ["*@example.com"] :session-ttl-days 7}
   :mail {:host "127.0.0.1" :port 1 :starttls false :from "jumble@example.com"}})

(deftest incomplete-config-test
  (testing "a missing setting fails when the system is built, naming the key"
    (is (thrown-with-msg? IllegalArgumentException #"Missing required key: :port"
                          (jumble/system (update (config "unused.db") :http dissoc :port))))
    (is (thrown-with-msg? IllegalArgumentException #"Missing required key: :db"
                          (jumble/system (dissoc (config "unused.db") :db))))))

(deftest hosting-settings-test
  (testing "the hosting settings reach the application"
    (let [hosting {:member-limit 20 :metrics-token "t" :moderator-notice "/n.md"
                   :hosted-by {:name "Jumble" :url "https://jumbl.ing"}}]
      (is (= hosting (select-keys (get-in (jumble/system (merge (config "unused.db") hosting)) [:server :settings])
                                  (keys hosting)))))))

(deftest delivery-settings-test
  (testing "emails go to those allowed in, by the rule the application signs them in with"
    (let [system (jumble/system (config "unused.db"))]
      (is (= ["*@example.com"] (get-in system [:delivery :settings :allowed-emails])))
      (is (= (get-in system [:server :settings :allowed-emails]) (get-in system [:delivery :settings :allowed-emails]))))))

(deftest system-test
  (call-with-db-path (fn [path]
                       (let [system (component/start
                                     (jumble/system (config path)))]
                         (try
                           (let [port (enso/port (get-in system [:server :server]))]
                             (testing "serves the health endpoint backed by the database"
                               (is (= {:status 200 :body "ok"} (http-get port "/healthz"))))
                             (testing "sign-in is wired to the provider, unreachable here"
                               (is (= 503 (:status (http-get port "/login")))))
                             (testing "uploads large enough for a listing's photos reach the application"
                               (let [request (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port "/listings")))
                                                 (.header "content-type" "multipart/form-data; boundary=x")
                                                 (.POST (HttpRequest$BodyPublishers/ofByteArray (byte-array (* 12 1024 1024))))
                                                 (.build))
                                     ^HttpResponse response (with-open [client (HttpClient/newHttpClient)]
                                                              (.send client request (HttpResponse$BodyHandlers/discarding)))]
                                 (is (= 403 (.statusCode response)) "refused by the origin check, not by size")))
                             (testing "the currency is pinned on start"
                               (is (= "CHF" (:setting/value (jdbc/execute-one! (get-in system [:db :datasource])
                                                                               ["SELECT value FROM setting WHERE key = 'currency'"])))))
                             (testing "the application knows its moderators"
                               (is (= #{"dave@example.com"} (get-in system [:server :settings :moderators]))))
                             (testing "message emails are sent from the background"
                               (is (some? (get-in system [:delivery :executor]))))
                             (testing "migrations ran on start"
                               (is (= (count (migrate/migrations))
                                      (migrate/version (get-in system [:db :datasource]))))))
                           (finally
                             (component/stop system)))))))
