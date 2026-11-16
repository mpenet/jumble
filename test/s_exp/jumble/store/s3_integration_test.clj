(ns ^:integration s-exp.jumble.store.s3-integration-test
  "Against the local Garage from docker compose (see dev/s3-init.sh)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.http :as http]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.store.blob-contract :as contract]
            [s-exp.jumble.store.s3 :as s3]
            [s-exp.jumble.token]))

(set! *warn-on-reflection* true)

(def config
  {:endpoint "http://127.0.0.1:3900"
   :region "garage"
   :bucket "jumble"
   :access-key "GK0123456789abcdef01234567"
   :secret-key "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"})

(deftest signature-checked-test
  (testing "the server verifies our signatures: a wrong secret is refused"
    (let [e (try
              (s3/put! (s3/client (assoc config :secret-key (apply str (repeat 64 "0"))))
                       "test/x"
                       (byte-array 1)
                       {"content-type" "image/jpeg"})
              nil
              (catch clojure.lang.ExceptionInfo e
                e))]
      (is (= 403 (:status (ex-data e)))))))

(deftest s3-store-test
  (contract/check (blob/s3-store config)))

(deftest presigned-url-test
  (let [store (blob/s3-store config)
        key (str "test/" (s-exp.jumble.token/generate) "/full.jpg")
        data (.getBytes "photo bytes")]
    (blob/put! store key data "image/jpeg")
    (try
      (testing "a plain GET of the presigned URL, without credentials, gets the object"
        (let [{:keys [status headers ^java.io.InputStream body]} (http/request (http/client {:connect-timeout 5000})
                                                                               {:method :get :url (blob/url store key (java.time.Instant/now))}
                                                                               :stream)]
          (is (= 200 status))
          (is (= (seq data)
                 (seq (with-open [in body]
                        (.readAllBytes in)))))
          (testing "objects never change, so browsers may keep them as long as the URL lives"
            (is (= "private, max-age=172800, immutable" (get headers "cache-control"))))))
      (testing "tampering with the URL breaks the signature"
        (let [url (str/replace (blob/url store key (java.time.Instant/now)) "full.jpg" "thumb.jpg")]
          (is (= 403 (:status (http/request (http/client {:connect-timeout 5000}) {:method :get :url url} :string))))))
      (finally
        (blob/delete! store key)))))
