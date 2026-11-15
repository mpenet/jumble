(ns s-exp.jumble.error
  "Errors as s-exp.ex exceptions, `(ex/ex-info message ::error/write-lock-timeout
  data)`, each type deriving from a category, and the responses they get:
  [[ex->response]]'s by category (see error.view), under the `:http/*` keys
  of their ex-data. Depends on nothing of Jumble's, so any namespace can
  require it and catch by category."
  (:require [clojure.tools.logging :as log]
            [s-exp.ex :as ex]))

(set! *warn-on-reflection* true)

(def ^:private types
  "Jumble's types under their category: one of s-exp.ex's or one of these."
  {::ex/incorrect #{::too-large ::not-an-image ::unsupported-format ::invalid-pattern
                    ::missing-config ::invalid-config ::invalid-logo}
   ::too-large #{::body-too-large ::too-many-pixels ::too-slow}
   ::ex/forbidden #{::invalid-token ::nonce-mismatch ::invalid-azp ::email-not-verified}
   ::ex/conflict #{::community-full ::account-deleted ::owner-gone ::setting-changed}
   ::ex/busy #{::busy}
   ::busy #{::write-lock-timeout ::images-busy}
   ::ex/unavailable #{::provider-unreachable ::discovery-failed ::issuer-mismatch ::keys-unavailable
                      ::token-exchange-failed ::storage-failed}
   ::ex/fault #{::migration-failed ::dangling-foreign-keys ::schema-too-new ::nested-write-tx
                ::no-catalog ::no-plural-rule ::missing-asset ::unknown-asset}})

(doseq [[category ts] types
        t ts]
  (ex/derive t category))

(defmulti ex->response
  "The response to exception `e`, thrown handling `request`, by its type's
  category: a page saying what kind of failure it is, never its message.
  Any other: a failure (500). The methods are in error.view."
  (fn [e _request] (ex/ex-type e))
  :hierarchy ex/hierarchy)

(defn- with-overrides
  "`response` with the `:http/status` and `:http/body` of ex-data `data`
  over its own, and its `:http/headers` merged into its own."
  [response {:http/keys [status headers body] :as data}]
  (cond-> response
    status (assoc :status status)
    headers (update :headers merge headers)
    (contains? data :http/body) (assoc :body body)))

(defn respond
  "The response to `e`, thrown handling `request`: [[ex->response]]'s, under
  its ex-data's `:http/*` keys. Busy (503) is logged as a warning, other
  failures (5xx) with their trace. An interrupt stays on the thread."
  [e request]
  (when (instance? InterruptedException e)
    (.interrupt (Thread/currentThread)))
  (let [{:keys [status] :as response} (with-overrides (ex->response e request) (ex-data e))]
    (cond
      (= 503 status) (log/warn "Busy:" (ex/ex-type e) (ex-message e))
      (<= 500 status) (log/error e "Request failed" (:request-method request) (:uri request)))
    response))

(defn wrap-errors
  "`handler` answering whatever it throws by [[respond]]."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Throwable t
        (respond t request)))))
