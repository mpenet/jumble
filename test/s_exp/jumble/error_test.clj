(ns s-exp.jumble.error-test
  "Errors as responses: by category, the ex-data's :http/* keys over them,
  interrupts kept."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.ex :as ex]
            [s-exp.jumble.error :as error]
            [s-exp.jumble.error.view]))

(set! *warn-on-reflection* true)

(def ^:private request
  {:request-method :get :uri "/x" :csp-nonce "n" :ctx {:settings {:app-name "Jumble"}}})

(defn- respond
  [e]
  ((error/wrap-errors (fn [_] (throw e))) request))

(deftest failure-test
  (testing "a failure becomes the error page, not a stack trace"
    (let [{:keys [status headers body]} (respond (RuntimeException. "boom"))]
      (is (= 500 status))
      (is (= "text/html; charset=utf-8" (get headers "content-type")))
      (is (.contains ^String body "Something went wrong"))
      (is (not (.contains ^String body "boom")))))
  (testing "an ex-info of no known type is a failure too"
    (is (= 500 (:status (respond (ex-info "boom" {:type ::unknown})))))))

(deftest category-test
  (testing "each category answers with its status"
    (doseq [[t status] {::ex/incorrect 400
                        ::ex/forbidden 403
                        ::ex/not-found 404
                        ::ex/unsupported 405
                        ::ex/conflict 409
                        ::ex/busy 429
                        ::ex/fault 500
                        ::ex/unavailable 502
                        ::error/too-large 413
                        ::error/busy 503}]
      (testing t
        (let [{:keys [headers body] :as response} (respond (ex/ex-info "internal detail" t))]
          (is (= status (:status response)))
          (is (= "text/html; charset=utf-8" (get headers "content-type")))
          (is (not (.contains ^String body "internal detail")) "the message is not shown"))))))

(deftest type-test
  (testing "writes queued too long ask the browser to retry later"
    (let [{:keys [status headers]} (respond (ex/ex-info "Timed out" ::error/write-lock-timeout))]
      (is (= 503 status))
      (is (= "5" (get headers "retry-after")))))
  (testing "a body over the limit is too large"
    (is (= 413 (:status (respond (ex/ex-info "Too large" ::error/body-too-large))))))
  (testing "Jumble's types answer by their category"
    (doseq [[t status] {::error/not-an-image 400
                        ::error/invalid-pattern 400
                        ::error/unsupported-format 400
                        ::error/too-many-pixels 413
                        ::error/owner-gone 409
                        ::error/storage-failed 502
                        ::error/missing-config 400
                        ::error/invalid-config 400
                        ::error/invalid-logo 400
                        ::error/setting-changed 409
                        ::error/migration-failed 500
                        ::error/provider-unreachable 502
                        ::error/discovery-failed 502
                        ::error/issuer-mismatch 502
                        ::error/keys-unavailable 502
                        ::error/token-exchange-failed 502
                        ::error/invalid-token 403
                        ::error/nonce-mismatch 403
                        ::error/invalid-azp 403
                        ::error/email-not-verified 403
                        ::error/community-full 409}]
      (testing t
        (is (= status (:status (respond (ex/ex-info "x" t)))))))))

(deftest override-test
  (testing "the ex-data's :http/status, :http/headers and :http/body go over the category's response"
    (let [{:keys [status headers body]} (respond (ex/ex-info "Taken" ::ex/conflict
                                                             {:http/status 422
                                                              :http/headers {"x-reason" "taken"
                                                                             "content-type" "text/plain"}
                                                              :http/body "That name is taken."}))]
      (is (= 422 status))
      (is (= {"x-reason" "taken" "content-type" "text/plain"} (select-keys headers ["x-reason" "content-type"])))
      (is (= "That name is taken." body))))
  (testing "headers merge into the category's"
    (let [{:keys [headers]} (respond (ex/ex-info "Busy" ::error/busy {:http/headers {"x-reason" "busy"}}))]
      (is (= "5" (get headers "retry-after")))
      (is (= "busy" (get headers "x-reason")))))
  (testing "keys left out keep the category's"
    (let [{:keys [status body]} (respond (ex/ex-info "Gone" ::ex/not-found {:http/headers {"x-reason" "gone"}}))]
      (is (= 404 status))
      (is (.contains ^String body "Not found"))))
  (testing "any ex-info may set them, of a known type or not"
    (is (= 418 (:status (respond (ex-info "Teapot" {:http/status 418})))))))

(deftest interrupt-test
  (testing "an interrupt answered stays on the thread"
    ;; Read before any assertion: reporting one can clear the flag.
    (let [{:keys [status]} (respond (InterruptedException.))
          interrupted (Thread/interrupted)]
      (is (= 500 status))
      (is interrupted))))
