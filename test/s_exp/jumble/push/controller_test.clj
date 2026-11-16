(ns s-exp.jumble.push.controller-test
  "Turning browser notifications on and off from the settings page, and the
  service worker showing them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture request sign-in!]]
            [s-exp.jumble.push.crypto :as crypto]
            [s-exp.jumble.push.model :as push])
  (:import (java.util Base64)))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(defn- b64 ^String [^bytes bs] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- subscription
  "As a browser's PushSubscription.toJSON() gives it."
  [endpoint]
  {"endpoint" endpoint
   "expirationTime" nil
   "keys" {"p256dh" (b64 (crypto/public-bytes (:public (crypto/generate-keys))))
           "auth" (b64 (byte-array (range 16)))}})

(def ^:private phone "https://fcm.googleapis.com/fcm/send/phone")

(deftest service-worker-test
  (testing "served from the root, so it covers every page, and checked for updates"
    (let [{:keys [status headers body]} (request :get "/sw.js" nil)]
      (is (= 200 status))
      (is (str/starts-with? (get headers "content-type") "text/javascript"))
      (is (= "no-cache" (get headers "cache-control")))
      (is (str/includes? body "showNotification")))))

(deftest subscribe-test
  (let [token (sign-in! "alice@example.com")
        alice (fixtures/user-id "alice@example.com")]
    (testing "signed in only"
      (is (= 401 (:status (request :post "/push/subscriptions" nil :json (subscription phone))))))
    (testing "a device's subscription is kept"
      (is (= 204 (:status (request :post "/push/subscriptions" token :json (subscription phone)))))
      (is (= 1 (count (push/devices *db* alice)))))
    (testing "not one to anywhere but a push service"
      (is (= 422 (:status (request :post
                                   "/push/subscriptions"
                                   token
                                   :json (subscription "https://169.254.169.254/x")))))
      (is (= 422 (:status (request :post "/push/subscriptions" token :json {"endpoint" phone}))))
      (is (= 400 (:status (request :post "/push/subscriptions" token :json "not an object")))))
    (testing "only JSON: a multipart body, which escapes the form size limit, is refused"
      (is (= 415 (:status (request :post
                                   "/push/subscriptions"
                                   token
                                   :form {"endpoint" phone}
                                   :headers {"content-type" "multipart/form-data; boundary=x"})))))
    (testing "the settings page lists it, with the key to subscribe with"
      (let [body (:body (request :get "/settings" token))]
        (is (str/includes? body (str "data-device=\"" (push/endpoint-key phone) "\"")))
        (is (str/includes? body (str "data-application-key=\"" (crypto/application-key (push/keys! *db*)) "\"")))
        (is (re-find #"<script type=\"module\" src=\"/assets/push\.[0-9a-f]+\.js\">" body))))
    (testing "turned off on the device itself"
      (is (= 204 (:status (request :post "/push/subscriptions/delete" token :json {"endpoint" phone}))))
      (is (empty? (push/devices *db* alice))))
    (testing "or removed from the list, for a device lost or given away"
      (request :post "/push/subscriptions" token :json (subscription phone))
      (let [{:keys [id]} (first (push/devices *db* alice))
            response (request :post (str "/settings/devices/" id "/delete") token :form {})]
        (is (= 303 (:status response)))
        (is (= "/settings" (get-in response [:headers "location"])))
        (is (empty? (push/devices *db* alice)))))))

(deftest trusted-types-test
  (testing "pages let the settings page register the service worker, through a policy of its own"
    (is (re-find #"trusted-types datastar jumble-push"
                 (get-in (request :get "/settings" (sign-in! "alice@example.com")) [:headers "content-security-policy"])))))
