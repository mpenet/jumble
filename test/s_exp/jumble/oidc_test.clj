(ns s-exp.jumble.oidc-test
  "The OpenID Connect client against the fake provider: discovery, the code
  flow, and every ID token it refuses."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [s-exp.ex :as ex]
            [s-exp.jumble.error :as-alias error]
            [s-exp.jumble.fake-idp :as idp]
            [s-exp.jumble.oidc :as oidc]))

(set! *warn-on-reflection* true)

(def ^:dynamic *idp* nil)

(use-fixtures :each
  (fn [t]
    (let [p (idp/start!)]
      (try
        (binding [*idp* p] (t))
        (finally (idp/stop! p))))))

(def redirect-uri "http://jumble.test/auth/callback")

(defn- provider
  ([] (provider *idp*))
  ([p]
   (oidc/provider {:issuer (:issuer p)
                   :client-id idp/client-id
                   :client-secret idp/client-secret
                   :redirect-uri redirect-uri})))

(defn- rejection
  "The type of the ex-info `f` throws, or ::accepted."
  [f]
  (try (f) ::accepted
       (catch clojure.lang.ExceptionInfo e (ex/ex-type e))))

(deftest pkce-test
  (testing "S256 challenge matches RFC 7636 appendix B"
    (is (= "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
           (oidc/code-challenge "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")))))

(deftest authorization-url-test
  (let [url (oidc/authorization-url (provider) {:state "st" :nonce "no" :code-verifier "ve"})
        params (idp/query-params url)]
    (is (.startsWith ^String url (str (:issuer *idp*) "/auth?")))
    (is (= {"response_type" "code"
            "client_id" idp/client-id
            "redirect_uri" redirect-uri
            "scope" "openid email profile"
            "state" "st"
            "nonce" "no"
            "code_challenge" (oidc/code-challenge "ve")
            "code_challenge_method" "S256"}
           params))))

(deftest discovery-issuer-mismatch-test
  (let [p (idp/start! {:discovery {:issuer "https://evil.example.com"}})]
    (try
      (is (= ::error/issuer-mismatch
             (rejection #(oidc/authorization-url (provider p) {:state "s" :nonce "n" :code-verifier "v"}))))
      (finally (idp/stop! p)))))

(deftest incomplete-discovery-test
  (doseq [missing [:token_endpoint :jwks_uri :authorization_endpoint]]
    (let [p (idp/start! {:discovery {missing nil}})]
      (try
        (is (= ::error/discovery-failed
               (rejection #(oidc/authorization-url (provider p) {:state "s" :nonce "n" :code-verifier "v"})))
            (name missing))
        (finally (idp/stop! p))))))

(deftest code-flow-test
  (let [p (provider)
        verifier "a-verifier-of-sufficient-length-0123456789abc"
        callback (idp/authorize! *idp* (oidc/authorization-url p {:state "s" :nonce "n1" :code-verifier verifier}))
        code (get (idp/query-params callback) "code")]
    (testing "exchange then verify yields the user's identity"
      (is (= {:iss (:issuer *idp*) :sub "user-1" :email "alice@example.com" :name "Alice" :hosted-domain nil}
             (oidc/verify-id-token p (oidc/exchange-code! p code verifier) "n1"))))
    (testing "codes are single use"
      (is (= ::error/token-exchange-failed (rejection #(oidc/exchange-code! p code verifier)))))))

(deftest pkce-verifier-mismatch-test
  (let [p (provider)
        callback (idp/authorize! *idp* (oidc/authorization-url p {:state "s" :nonce "n" :code-verifier "right"}))
        code (get (idp/query-params callback) "code")]
    (is (= ::error/token-exchange-failed (rejection #(oidc/exchange-code! p code "wrong"))))))

(deftest id-token-rejections-test
  (let [p (provider)
        now (quot (System/currentTimeMillis) 1000)
        verify #(rejection (fn [] (oidc/verify-id-token p % "n")))
        token #(idp/id-token *idp* (merge {:nonce "n"} %))]
    (is (= ::accepted (verify (token {}))) "baseline")
    (testing "signature"
      (is (= ::error/invalid-token (verify (idp/id-token *idp* {:nonce "n"} {:key (idp/rsa-key "k1")})))
          "same kid, key not published by the provider")
      (is (= ::error/invalid-token (verify (idp/sign-hs256 (byte-array 32) (merge (idp/default-claims *idp*) {:nonce "n"}))))
          "HMAC algorithms are not accepted")
      (is (= ::error/invalid-token (verify (idp/unsigned (merge (idp/default-claims *idp*) {:nonce "n"}))))
          "alg none")
      (is (= ::error/invalid-token (verify "not.a.jwt"))))
    (testing "claims"
      (is (= ::error/invalid-token (verify (token {:exp (- now 120)}))) "expired")
      (is (= ::error/invalid-token (verify (token {:iss "https://other.example.com"}))))
      (is (= ::error/invalid-token (verify (token {:aud "another-client"}))))
      (is (= ::error/invalid-token (verify (token {:sub nil}))))
      (is (= ::error/invalid-token (verify (token {:email nil}))))
      (is (= ::error/invalid-token (verify (token {:email 42}))))
      (is (= ::error/nonce-mismatch (verify (token {:nonce "other"}))))
      (is (= ::error/nonce-mismatch (verify (token {:nonce nil}))))
      (is (= ::error/email-not-verified (verify (token {:email_verified false}))))
      (is (= ::error/email-not-verified (verify (token {:email_verified nil})))))
    (testing "several audiences require us as authorized party"
      (is (= ::error/invalid-azp (verify (token {:aud [idp/client-id "other"]}))))
      (is (= ::accepted (verify (token {:aud [idp/client-id "other"] :azp idp/client-id})))))
    (testing "name falls back to the email address"
      (is (= "alice@example.com" (:name (oidc/verify-id-token p (token {:name nil}) "n"))))
      (is (= "alice@example.com" (:name (oidc/verify-id-token p (token {:name " "}) "n")))
          "a blank name is no name"))
    (testing "the organisation managing the account, when the provider says (Google's hd)"
      (is (= "example.com" (:hosted-domain (oidc/verify-id-token p (token {:hd "example.com"}) "n")))))))

(deftest key-rotation-test
  (let [p (provider)]
    (is (map? (oidc/verify-id-token p (idp/id-token *idp* {:nonce "n"}) "n")))
    (idp/rotate-key! *idp* "k2")
    (testing "a token under a new kid triggers a JWKS refetch"
      (is (map? (oidc/verify-id-token p (idp/id-token *idp* {:nonce "n"}) "n"))))))

(deftest malformed-keys-address-test
  (testing "a keys address that is not an absolute URL is a failed discovery"
    (doseq [jwks-uri ["/keys" "not a url" "ftp://127.0.0.1/keys"]]
      (let [p (idp/start! {:discovery {:jwks_uri jwks-uri}})]
        (try
          (is (= ::error/discovery-failed
                 (rejection #(oidc/authorization-url (provider p) {:state "s" :nonce "n" :code-verifier "v"})))
              jwks-uri)
          (finally (idp/stop! p)))))))

(deftest malformed-token-response-test
  (testing "an ID token that is not a string is a failed exchange"
    (doseq [id-token [42 {"a" 1} nil]]
      (let [p (idp/start! {:token {:id_token id-token}})
            verifier "a-verifier-of-sufficient-length-0123456789abc"
            code (get (idp/query-params (idp/authorize! p (oidc/authorization-url (provider p)
                                                                                  {:state "s" :nonce "n"
                                                                                   :code-verifier verifier})))
                      "code")]
        (try
          (is (= ::error/token-exchange-failed (rejection #(oidc/exchange-code! (provider p) code verifier)))
              (pr-str id-token))
          (finally (idp/stop! p)))))))
