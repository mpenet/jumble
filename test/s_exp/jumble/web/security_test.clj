(ns s-exp.jumble.web.security-test
  "Security headers, the content security policy's nonce among them."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.web.security :as security]))

(set! *warn-on-reflection* true)

(defn- csp-nonce
  [response]
  (second (re-find #"'nonce-([^']+)'" (get-in response [:headers "content-security-policy"]))))

(deftest headers-test
  (let [seen (atom nil)
        handler (security/wrap-security-headers
                 (fn [request] (reset! seen (:csp-nonce request)) {:status 200 :headers {} :body "ok"})
                 {:base-url "https://jumble.example.com" :image-origin "https://objects.example.com"})
        response (handler {:request-method :get :uri "/"})
        headers (:headers response)]
    (testing "the handler sees the nonce the policy allows"
      (is (re-matches #"[A-Za-z0-9_-]{22,}" @seen))
      (is (= @seen (csp-nonce response))))
    (testing "scripts only from us or carrying the nonce; no eval, no inline handlers"
      (let [csp (get headers "content-security-policy")]
        (is (re-find #"script-src 'self' 'nonce-[^']+'(;|$)" csp))
        (is (not (re-find #"unsafe" csp)))
        (doseq [directive ["default-src 'self'" "img-src 'self' https://objects.example.com" "object-src 'none'" "base-uri 'none'"
                           "frame-ancestors 'none'" "form-action 'self'"
                           "require-trusted-types-for 'script'" "trusted-types datastar"]]
          (is (.contains ^String csp directive) directive))))
    (testing "other headers"
      (is (= "nosniff" (get headers "x-content-type-options")))
      (is (= "same-origin" (get headers "referrer-policy")))
      (is (= "same-origin" (get headers "cross-origin-opener-policy")))
      (is (= "no-store" (get headers "cache-control")))
      (is (= "max-age=31536000" (get headers "strict-transport-security"))))))

(deftest nonce-per-request-test
  (let [handler (security/wrap-security-headers (constantly {:status 200}) {:base-url "https://jumble.example.com" :image-origin "https://objects.example.com"})]
    (is (= 20 (count (into #{} (map (fn [_] (csp-nonce (handler {})))) (range 20)))))))

(deftest local-http-test
  (testing "HSTS only over https"
    (let [handler (security/wrap-security-headers (constantly {:status 200}) {:base-url "http://localhost:8080" :image-origin "http://127.0.0.1:3900"})]
      (is (nil? (get-in (handler {}) [:headers "strict-transport-security"]))))))

(deftest handler-headers-win-test
  (let [handler (security/wrap-security-headers
                 (constantly {:status 200 :headers {"cache-control" "public, max-age=31536000, immutable"}})
                 {:base-url "https://jumble.example.com" :image-origin "https://objects.example.com"})]
    (is (= "public, max-age=31536000, immutable"
           (get-in (handler {}) [:headers "cache-control"])))))
