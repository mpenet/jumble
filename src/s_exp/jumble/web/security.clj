(ns s-exp.jumble.web.security
  "Security headers on every response, the content security policy with its
  per-request nonce among them."
  (:require [clojure.string :as str]
            [s-exp.jumble.token :as token]))

(set! *warn-on-reflection* true)

(defn- content-security-policy
  ;; Datastar evaluates its expressions through scripts carrying the nonce it
  ;; reads from <html data-nonce>, under its "datastar" Trusted Types policy,
  ;; so neither 'unsafe-eval' nor 'unsafe-inline' is needed. The settings
  ;; page registers the service worker under "jumble-push".
  [nonce image-origin]
  (str/join "; "
            ["default-src 'self'"
             ;; Photos load straight from object storage via presigned URLs.
             (str "img-src 'self'" (when image-origin (str " " image-origin)))
             (str "script-src 'self' 'nonce-" nonce "'")
             "object-src 'none'"
             "base-uri 'none'"
             "frame-ancestors 'none'"
             "form-action 'self'"
             "require-trusted-types-for 'script'"
             "trusted-types datastar jumble-push"]))

(defn wrap-security-headers
  "Adds browser hardening headers to every response, without overriding
  ones the handler set. The request gets the `:csp-nonce` pages must put on
  <html data-nonce> for Datastar. Responses default to `no-store`: pages
  show per-user data."
  [handler {:keys [base-url image-origin]}]
  (let [https (str/starts-with? base-url "https://")]
    (fn [request]
      (let [n (token/generate 16)
            response (handler (assoc request :csp-nonce n))]
        (update response
                :headers
                #(merge (cond-> {"content-security-policy" (content-security-policy n image-origin)
                                 "x-content-type-options" "nosniff"
                                 "referrer-policy" "same-origin"
                                 "cross-origin-opener-policy" "same-origin"
                                 "cache-control" "no-store"}
                          https (assoc "strict-transport-security" "max-age=31536000"))
                        %))))))
