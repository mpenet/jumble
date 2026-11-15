(ns s-exp.jumble.fake-idp
  "A local OpenID Connect provider for tests: discovery, keys, the token
  endpoint and signed ID tokens, each overridable."
  (:require [clojure.string :as str]
            [ring.util.codec :as codec]
            [s-exp.enso :as enso]
            [s-exp.oda :as oda])
  (:import (com.nimbusds.jose JWSAlgorithm JWSHeader$Builder)
           (com.nimbusds.jose.crypto MACSigner RSASSASigner)
           (com.nimbusds.jose.jwk JWKSet RSAKey)
           (com.nimbusds.jose.jwk.gen RSAKeyGenerator)
           (com.nimbusds.jwt JWTClaimsSet PlainJWT SignedJWT)
           (java.io InputStream)
           (java.net URI)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64 List Map)))

(set! *warn-on-reflection* true)

(def client-id "jumble")
(def client-secret "s3cr3t:with/odd chars")

(defn rsa-key
  ^RSAKey [kid]
  (.generate (.keyID (RSAKeyGenerator. 2048) kid)))

(defn- now-seconds [] (quot (System/currentTimeMillis) 1000))

(defn- claims-set
  ^JWTClaimsSet [claims]
  (JWTClaimsSet/parse ^Map (update-keys (into {} (remove (comp nil? val)) claims) name)))

(defn sign
  "Signs `claims` (nil values dropped) with RS256 under `key`'s kid."
  [^RSAKey key claims]
  (let [header (.build (.keyID (JWSHeader$Builder. JWSAlgorithm/RS256) (.getKeyID key)))
        jwt (SignedJWT. header (claims-set claims))]
    (.sign jwt (RSASSASigner. key))
    (.serialize jwt)))

(defn sign-hs256
  [^bytes secret claims]
  (let [jwt (SignedJWT. (.build (JWSHeader$Builder. JWSAlgorithm/HS256)) (claims-set claims))]
    (.sign jwt (MACSigner. secret))
    (.serialize jwt)))

(defn unsigned
  [claims]
  (.serialize (PlainJWT. (claims-set claims))))

(defn default-claims
  [idp]
  (let [now (now-seconds)]
    {:iss (:issuer idp) :aud client-id :sub "user-1"
     :email "alice@example.com" :email_verified true :name "Alice"
     :iat now :exp (+ now 300)}))

(defn id-token
  "ID token with default claims merged with `claims`, signed by the IdP's
  current key unless `:key` is given."
  ([idp claims] (id-token idp claims nil))
  ([idp claims {:keys [key]}]
   (sign (or key (:signing-key @(:state idp))) (merge (default-claims idp) claims))))

(defn rotate-key!
  "Replaces the published key and the signing key with a fresh one."
  [idp kid]
  (let [k (rsa-key kid)]
    (swap! (:state idp) assoc :signing-key k :keys [k])))

(defn- json
  [status body]
  {:status status
   :headers {"content-type" "application/json"}
   :body (oda/write-str body)})

(defn- s256
  [^String verifier]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.digest (MessageDigest/getInstance "SHA-256")
                            (.getBytes verifier StandardCharsets/US_ASCII))))

(defn- basic-credentials
  [{:keys [headers]}]
  (when-let [[_ encoded] (some->> (get headers "authorization") (re-matches #"Basic (.+)"))]
    (let [[id secret] (str/split (String. (.decode (Base64/getDecoder) ^String encoded)
                                          StandardCharsets/UTF_8)
                                 #":" 2)]
      [(codec/form-decode-str id) (codec/form-decode-str secret)])))

(defn- token-endpoint
  [state request]
  (let [params (codec/form-decode (slurp ^InputStream (:body request)))
        code (get params "code")
        grant (get-in @state [:codes code])]
    (swap! state update :codes dissoc code)
    (cond
      (not= [client-id client-secret] (basic-credentials request))
      (json 401 {:error "invalid_client"})

      (or (not= "authorization_code" (get params "grant_type"))
          (nil? grant)
          (not= (:redirect-uri grant) (get params "redirect_uri"))
          (not= (:challenge grant) (some-> (get params "code_verifier") s256)))
      (json 400 {:error "invalid_grant"})

      :else
      (json 200 (merge {:access_token "unused" :token_type "Bearer" :expires_in 300
                        :id_token (:id-token grant)}
                       (:token @state))))))

(defn- handler
  [state]
  (fn [{:keys [request-method uri] :as request}]
    (let [{:keys [issuer discovery keys]} @state]
      (case [request-method uri]
        [:get "/.well-known/openid-configuration"]
        (json 200 (into {}
                        (remove (comp nil? val))
                        (merge {:issuer issuer
                                :authorization_endpoint (str issuer "/auth")
                                :token_endpoint (str issuer "/token")
                                :jwks_uri (str issuer "/keys")
                                :id_token_signing_alg_values_supported ["RS256"]}
                               discovery)))

        [:get "/keys"]
        (json 200 (oda/parse (str (.toPublicJWKSet (JWKSet. ^List keys)))))

        [:post "/token"]
        (token-endpoint state request)

        {:status 404}))))

(defn start!
  "Starts the provider. `:discovery` overrides entries of its discovery
  document, nil values removing them; `:token` those of its token responses."
  ([] (start! nil))
  ([{:keys [discovery token]}]
   (let [k (rsa-key "k1")
         state (atom {:signing-key k :keys [k] :codes {} :discovery discovery :token token})
         server (enso/run-server (handler state) {:host "127.0.0.1" :port 0})
         issuer (str "http://127.0.0.1:" (enso/port server))]
     (swap! state assoc :issuer issuer)
     {:issuer issuer :state state :server server})))

(defn stop!
  [idp]
  (enso/stop (:server idp)))

(defn query-params
  [^String url]
  (codec/form-decode (.getRawQuery (URI. url))))

(defn authorize!
  "Plays the user signing in at the provider: validates the authorization
  request in `auth-url`, issues a code bound to it, and returns the callback
  URL the browser would be sent to. `claims` override the ID token's."
  ([idp auth-url] (authorize! idp auth-url nil))
  ([idp auth-url claims]
   (let [{:strs [client_id response_type code_challenge code_challenge_method
                 redirect_uri state nonce scope]} (query-params auth-url)
         code (str (random-uuid))]
     (assert (= client-id client_id))
     (assert (= "code" response_type))
     (assert (= "S256" code_challenge_method))
     (assert (= #{"openid" "email" "profile"} (set (str/split scope #" "))))
     (swap! (:state idp) assoc-in [:codes code]
            {:challenge code_challenge
             :redirect-uri redirect_uri
             :id-token (id-token idp (merge {:nonce nonce} claims))})
     (str redirect_uri "?" (codec/form-encode {"code" code "state" state})))))
