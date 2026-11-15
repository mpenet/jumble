(ns s-exp.jumble.store.s3-test
  "The S3 client: request signing against AWS's examples, presigned URLs,
  failures."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.ex :as ex]
            [s-exp.jumble.error :as-alias error]
            [s-exp.jumble.http-test :as http-test]
            [s-exp.jumble.store.s3 :as s3]))

(set! *warn-on-reflection* true)

(def empty-sha256 "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

(deftest aws-sigv4-test-suite-test
  (testing "get-vanilla from the AWS Signature Version 4 test suite"
    (is (= (str "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
                "SignedHeaders=host;x-amz-date, "
                "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31")
           (s3/authorization {:method :get
                              :path "/"
                              :headers {"host" "example.amazonaws.com"
                                        "x-amz-date" "20150830T123600Z"}
                              :payload-hash empty-sha256}
                             {:access-key "AKIDEXAMPLE"
                              :secret-key "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
                              :region "us-east-1"
                              :service "service"})))))

(deftest s3-documentation-example-test
  (testing "GET object example from the S3 SigV4 header authentication docs"
    (is (= (str "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41")
           (s3/authorization {:method :get
                              :path "/test.txt"
                              :headers {"Host" "examplebucket.s3.amazonaws.com"
                                        "Range" "bytes=0-9"
                                        "x-amz-content-sha256" empty-sha256
                                        "x-amz-date" "20130524T000000Z"}
                              :payload-hash empty-sha256}
                             {:access-key "AKIAIOSFODNN7EXAMPLE"
                              :secret-key "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
                              :region "us-east-1"
                              :service "s3"})))))

(deftest canonical-path-test
  (testing "each segment is RFC 3986 encoded once; slashes separate segments"
    (is (= "/bucket/photos/a%20b/c%2Bd~e_f.jpg" (s3/canonical-path "/bucket/photos/a b/c+d~e_f.jpg")))
    (is (= "/v%C3%A9lo/A-Z.0" (s3/canonical-path "/vélo/A-Z.0")))))

(deftest presigned-query-documentation-example-test
  (testing "presigned URL example from the S3 SigV4 query string authentication docs"
    (is (= (str "X-Amz-Algorithm=AWS4-HMAC-SHA256"
                "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
                "&X-Amz-Date=20130524T000000Z"
                "&X-Amz-Expires=86400"
                "&X-Amz-SignedHeaders=host"
                "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404")
           (s3/presigned-query {:host "examplebucket.s3.amazonaws.com"
                                :path "/test.txt"
                                :amz-date "20130524T000000Z"
                                :expires 86400}
                               {:access-key "AKIAIOSFODNN7EXAMPLE"
                                :secret-key "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
                                :region "us-east-1"
                                :service "s3"})))))

(deftest presign-url-test
  (let [client (s3/client {:endpoint "https://objects.example.com" :region "eu-west-1" :bucket "jumble"
                           :access-key "EXO123" :secret-key "secret"})
        at (fn [s] (java.time.Instant/parse s))
        url (s3/presign-url client "photos/abc/full.jpg" (at "2026-10-28T09:15:00Z"))]
    (testing "path-style URL for the object"
      (is (.startsWith ^String url "https://objects.example.com/jumble/photos/abc/full.jpg?X-Amz-Algorithm=")))
    (testing "signed as of the start of the UTC day, valid 48 hours"
      (is (.contains ^String url "X-Amz-Date=20261028T000000Z"))
      (is (.contains ^String url "X-Amz-Expires=172800")))
    (testing "stable within a day, so browsers can cache the image"
      (is (= url (s3/presign-url client "photos/abc/full.jpg" (at "2026-10-28T23:59:59Z"))))
      (is (not= url (s3/presign-url client "photos/abc/full.jpg" (at "2026-10-29T00:00:00Z")))))))

(deftest failure-test
  (testing "a refused request is a storage failure, with the status and the server's error"
    (http-test/with-server (atom []) (fn [_] [403 {} "<Error><Code>AccessDenied</Code></Error>"])
      (fn [base]
        (let [client (s3/client {:endpoint base :region "r" :bucket "b" :access-key "a" :secret-key "s"})
              e (try (s3/put! client "k" (byte-array 1) {}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= ::error/storage-failed (ex/ex-type e)))
          (is (= {:operation "put" :key "k" :status 403} (select-keys (ex-data e) [:operation :key :status])))
          (is (re-find #"AccessDenied" (:error (ex-data e)))))))))
