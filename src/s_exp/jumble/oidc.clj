(ns s-exp.jumble.oidc
  "Signing in with an OpenID Connect provider: discovery, the authorization
  code flow with PKCE, and verifying the ID token it returns."
  (:require [clojure.string :as str]
            [ring.util.codec :as codec]
            [s-exp.ex :as ex]
            [s-exp.jumble.error :as error]
            [s-exp.jumble.http :as http]
            [s-exp.jumble.token :as token]
            [s-exp.oda :as oda])
  (:import (com.nimbusds.jose JOSEException JWSAlgorithm KeySourceException)
           (com.nimbusds.jose.jwk.source JWKSourceBuilder)
           (com.nimbusds.jose.proc BadJOSEException JWSVerificationKeySelector)
           (com.nimbusds.jwt JWTClaimsSet JWTClaimsSet$Builder)
           (com.nimbusds.jwt.proc DefaultJWTClaimsVerifier DefaultJWTProcessor)
           (java.io IOException)
           (java.net URI URISyntaxException)
           (java.nio.charset StandardCharsets)
           (java.text ParseException)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(def ^:private scope "openid email profile")

(def ^:private signing-algorithms
  ;; Asymmetric only: accepting HS* would let anyone holding the public key forge tokens.
  #{JWSAlgorithm/RS256 JWSAlgorithm/ES256})

(defn google?
  "Whether `issuer` is Google's. Google accounts can be registered with any
  verified address, work ones included, and outlive leaving the
  organisation: an address let in by its domain must also be managed by the
  organisation of that domain, which Google tells by the hd claim. Other
  providers know only their own members."
  [issuer]
  (= "https://accounts.google.com" (str/replace (str issuer) #"/+$" "")))

(defn provider
  "Relying party for `issuer`, requiring the organisation of addresses a
  domain let in when `require-hosted-domain` (see [[google?]]). Discovery
  happens on first use and is cached once it succeeds, so the application
  starts while the provider is down."
  [{:keys! [issuer client-id client-secret redirect-uri] :keys [require-hosted-domain]}]
  {:issuer issuer
   :client-id client-id
   :client-secret client-secret
   :redirect-uri redirect-uri
   :require-hosted-domain (boolean require-hosted-domain)
   :http (http/client {:connect-timeout 5000 :timeout 10000})
   :discovered (atom nil)})

(defn code-challenge
  "PKCE S256 challenge for `verifier` (RFC 7636)."
  [^String verifier]
  (token/base64url (token/sha256 (.getBytes verifier StandardCharsets/US_ASCII))))

(defn- send!
  "Sends `request` (see [[http/request]]), returning status and parsed
  JSON body (nil when not JSON). Network failures and timeouts throw
  ex-info of type ::error/provider-unreachable."
  [{:keys [http]} request]
  (let [{:keys [status body]} (try
                                (http/request http request :string)
                                (catch IOException e
                                  (throw (ex/ex-info "OIDC provider unreachable"
                                                     ::error/provider-unreachable
                                                     {:url (:url request)}
                                                     e))))]
    {:status status
     :body (try
             (oda/parse ^String body)
             (catch Exception _
               nil))}))

(defn- jwt-processor
  [{:keys [issuer client-id]} jwks-uri]
  (let [;; Caches keys, refetches on an unknown kid (rate limited), retries once
        ;; on network errors.
        keys (-> (JWKSourceBuilder/create (.toURL (URI. jwks-uri)))
                 (.retrying true)
                 (.build))]
    (doto (DefaultJWTProcessor.)
      (.setJWSKeySelector (JWSVerificationKeySelector. ^java.util.Set signing-algorithms keys))
      (.setJWTClaimsSetVerifier
       (DefaultJWTClaimsVerifier. ^String client-id
                                  (.build (.issuer (JWTClaimsSet$Builder.) issuer))
                                  #{"sub" "iat" "exp" "email"})))))

(defn- http-url?
  "Whether `s` is an absolute http or https URL with a host."
  [s]
  (let [^URI uri (when (string? s)
                   (try
                     (URI. s)
                     (catch URISyntaxException _
                       nil)))]
    (boolean (and uri (#{"http" "https"} (.getScheme uri)) (.getHost uri)))))

(defn- discover
  [{:keys [issuer] :as p}]
  (let [url (str (str/replace issuer #"/+$" "") "/.well-known/openid-configuration")
        {:keys [status body]} (send! p {:method :get :url url})
        {:strs [authorization_endpoint token_endpoint jwks_uri]} body]
    (when-not (= 200 status)
      (throw (ex/ex-info "OIDC discovery failed" ::error/discovery-failed {:url url :status status})))
    ;; OIDC Discovery 1.0 §4.3: the advertised issuer must be the one we asked.
    (when-not (= issuer (get body "issuer"))
      (throw (ex/ex-info "OIDC discovery issuer mismatch"
                         ::error/issuer-mismatch
                         {:expected issuer :actual (get body "issuer")})))
    (when-not (every? http-url? [authorization_endpoint token_endpoint jwks_uri])
      (throw (ex/ex-info "OIDC discovery document incomplete or malformed" ::error/discovery-failed {:url url})))
    {:authorization-endpoint authorization_endpoint
     :token-endpoint token_endpoint
     :processor (jwt-processor p jwks_uri)}))

(defn- discovered
  [{:keys [discovered] :as p}]
  (or @discovered (reset! discovered (discover p))))

(defn authorization-url
  "URL sending the browser to the provider. `state` ties the callback to the
  browser, `nonce` ties the ID token to this request, the challenge derived
  from `code-verifier` ties the code to whoever holds the verifier."
  [{:keys [client-id redirect-uri] :as p} {:keys! [state nonce code-verifier]}]
  (let [{:keys [^String authorization-endpoint]} (discovered p)]
    (str authorization-endpoint
         (if (.contains authorization-endpoint "?") "&" "?")
         (codec/form-encode {"response_type" "code"
                             "client_id" client-id
                             "redirect_uri" redirect-uri
                             "scope" scope
                             "state" state
                             "nonce" nonce
                             "code_challenge" (code-challenge code-verifier)
                             "code_challenge_method" "S256"}))))

(defn- basic-auth
  "RFC 6749 §2.3.1: id and secret are form-encoded before base64."
  [client-id client-secret]
  (let [credentials (str (codec/form-encode client-id) ":" (codec/form-encode client-secret))]
    (str "Basic " (.encodeToString (Base64/getEncoder)
                                   (.getBytes credentials StandardCharsets/UTF_8)))))

(defn exchange-code!
  "Exchanges an authorization `code` for the ID token (compact JWT string)."
  [{:keys [client-id client-secret redirect-uri] :as p} code code-verifier]
  (let [{:keys [token-endpoint]} (discovered p)
        {:keys [status body]} (send! p
                                     {:method :post
                                      :url token-endpoint
                                      :headers {"content-type" "application/x-www-form-urlencoded"
                                                "authorization" (basic-auth client-id client-secret)}
                                      :body (codec/form-encode {"grant_type" "authorization_code"
                                                                "code" code
                                                                "redirect_uri" redirect-uri
                                                                "code_verifier" code-verifier})})]
    (or (when (= 200 status) (let [t (get body "id_token")] (when (string? t) t)))
        (throw (ex/ex-info "OIDC token exchange failed"
                           ::error/token-exchange-failed
                           {:status status :error (get body "error")})))))

(defn- reject
  [t message]
  (throw (ex/ex-info message t)))

(defn verify-id-token
  "Verifies `id-token` was issued for us, by our provider, for the request
  that carried `nonce`, and returns the identity it asserts. Throws ex-info
  of a sign-in type of [[error]] otherwise."
  [{:keys [issuer client-id] :as p} ^String id-token nonce]
  (let [{:keys [^DefaultJWTProcessor processor]} (discovered p)
        ^JWTClaimsSet claims (try
                               (.process processor id-token nil)
                               (catch KeySourceException e
                                 (throw (ex/ex-info "Provider keys unavailable" ::error/keys-unavailable nil e)))
                               (catch BadJOSEException e
                                 (throw (ex/ex-info "Invalid ID token" ::error/invalid-token nil e)))
                               (catch JOSEException e
                                 (throw (ex/ex-info "Invalid ID token" ::error/invalid-token nil e)))
                               (catch ParseException e
                                 (throw (ex/ex-info "Invalid ID token" ::error/invalid-token nil e))))
        token-nonce (.getClaim claims "nonce")
        audience (.getAudience claims)]
    (when-not (and (string? token-nonce) (token/same? token-nonce nonce))
      (reject ::error/nonce-mismatch "ID token nonce mismatch"))
    ;; OIDC Core §3.1.3.7 (4, 5)
    (when (and (> (count audience) 1) (not= client-id (.getClaim claims "azp")))
      (reject ::error/invalid-azp "ID token authorized party is not this client"))
    (when-not (string? (.getClaim claims "email"))
      (reject ::error/invalid-token "ID token email is not a string"))
    (when-not (true? (.getClaim claims "email_verified"))
      (reject ::error/email-not-verified "Email address not verified by the provider"))
    (let [email (.getClaim claims "email")
          name (.getClaim claims "name")]
      {:iss issuer
       :sub (.getSubject claims)
       :email email
       :name (if (and (string? name) (not (str/blank? name))) name email)
       ;; Google's: the Workspace domain managing the account, absent for
       ;; personal accounts even when registered with a work address.
       :hosted-domain (let [hd (.getClaim claims "hd")] (when (string? hd) hd))})))
