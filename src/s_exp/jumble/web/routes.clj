(ns s-exp.jumble.web.routes
  "The application's handler: every context's routes behind the shared
  middleware, in the order they must run."
  (:require [clojure.string :as str]
            [ring.util.codec :as codec]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.head :refer [wrap-head]]
            [ring.middleware.params :refer [wrap-params]]
            [s-exp.appia :as appia]
            [s-exp.ex :as ex]
            [s-exp.jumble.account.controller :as account]
            [s-exp.jumble.page.assets :as assets]
            [s-exp.jumble.page.branding :as branding]
            [s-exp.jumble.view.dates :as dates]
            [s-exp.jumble.web.auth :as auth]
            [s-exp.jumble.error :as error]
            [s-exp.jumble.error.view]
            [s-exp.jumble.page.health :as health]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.home.controller :as home]
            [s-exp.jumble.view.layout :as layout]
            [s-exp.jumble.web.response :as response]
            [s-exp.jumble.listing.controller :as listing]
            [s-exp.jumble.message.controller :as message]
            [s-exp.jumble.push.controller :as push]
            [s-exp.jumble.page.metrics :as metrics-page]
            [s-exp.jumble.moderation.controller :as moderation]
            [s-exp.jumble.metrics :as metrics]
            [s-exp.jumble.page.manifest :as manifest]
            [s-exp.jumble.page.palette :as palette]
            [s-exp.jumble.page.privacy :as privacy]
            [s-exp.jumble.web.security :as security]
            [s-exp.jumble.page.theme :as theme])
  (:import (java.io InputStream)
           (java.net InetAddress URI URISyntaxException)))

(set! *warn-on-reflection* true)

(defn dispatcher
  "Ring handler routing to `routes`, a map of `[method path]` to handler.
  Handlers receive `:path-params` and `ctx` (system components) as `:ctx`.
  HEAD requests are served by the GET route, without a body. The route
  matched, its path pattern, is noted in the request's `::route` when it
  has one (see [[wrap-metrics]])."
  [routes ctx]
  (let [router (appia/router (into {}
                                   (map (fn [[[_ path :as k] handler]]
                                          [k (fn [request]
                                               (some-> (::route request) (vreset! path))
                                               (handler request))]))
                                   routes))]
    (wrap-head
     (fn [request]
       (if-let [[handler path-params] (appia/match router request)]
         (handler (assoc request :path-params path-params :ctx ctx))
         (auth/in-known-language (assoc request :ctx ctx) layout/not-found))))))

(def ^:private max-form-bytes
  "Bodies other than file uploads: forms and Datastar requests."
  (* 1024 1024))

(defn- too-large!
  []
  (throw (ex/ex-info "Request body too large" ::error/body-too-large)))

(defn- bounded
  "`in`, failing once more than `limit` bytes have been read from it."
  ^InputStream [^InputStream in limit]
  (let [n (volatile! 0)
        counted (fn [read]
                  (when (and (pos? read) (> (vswap! n + read) limit))
                    (too-large!))
                  read)]
    (proxy [InputStream] []
      (read
        ([] (let [b (.read in)] (when (>= b 0) (counted 1)) b))
        ([bs off len] (counted (.read in ^bytes bs (int off) (int len)))))
      (available [] (.available in))
      (close [] (.close in)))))

(defn wrap-body-limit
  "Caps request bodies at 1 MB, except multipart uploads, which are parsed
  only by the upload routes, after sign-in, under their own limits. Form
  parsing reads a whole body into memory, so it must never see a large one."
  [handler]
  (fn [{:keys [headers body] :as request}]
    (if (str/starts-with? (get headers "content-type" "") "multipart/form-data")
      (handler request)
      (do (when (some-> (get headers "content-length") parse-long (> max-form-bytes))
            (too-large!))
          (handler (cond-> request body (assoc :body (bounded body max-form-bytes))))))))

(defn- ip-literal?
  "Whether `s` is an IPv4 or IPv6 address as written, never looked up as a
  name."
  [s]
  (boolean (and (string? s) (try (InetAddress/ofLiteral s) (catch IllegalArgumentException _ nil)))))

(defn wrap-client-address
  "Behind a proxy (`behind-proxy`), takes the client's address as the last
  in the X-Forwarded-For header: the one the proxy added, which a client
  cannot forge, so long as only the proxy reaches Jumble. Otherwise, or
  when that is not an IP address, the connection's address stays."
  [handler behind-proxy]
  (if behind-proxy
    (fn [{:keys [headers] :as request}]
      (let [client (some-> (get headers "x-forwarded-for") (str/split #",") last str/trim)]
        (handler (cond-> request
                   (ip-literal? client) (assoc :remote-addr client)))))
    handler))

(defn- wrap-ctx
  "`request` holding `ctx` as `:ctx`, for the error pages (see
  [[error/wrap-errors]])."
  [handler ctx]
  (fn [request]
    (handler (assoc request :ctx ctx))))

(defn- referer-path
  "The path of the page `request` was sent from, nil when its Referer is no
  address: the header is the client's, whatever it holds."
  [request]
  (when-let [referer (get-in request [:headers "referer"])]
    (try (.getRawPath (URI. referer))
         (catch URISyntaxException _ nil))))

(def ^:private requests
  (metrics/counter "jumble_http_requests_total" "Requests answered, by method, route and status."))

(def ^:private durations
  (metrics/histogram "jumble_http_request_duration_seconds" "How long requests took to answer, by method and route."))

(def ^:private standard-methods
  #{:get :head :post :put :patch :delete :options})

(defn- wrap-metrics
  "Counts and times every request, by its route's path pattern (unmatched
  for none) rather than its path, and its method (other for one not
  standard): what a client sends cannot make series without end."
  [handler]
  (fn [{:keys [request-method] :as request}]
    (let [route (volatile! nil)
          start (System/nanoTime)
          record! (fn [status]
                    (let [labels {:method (if (standard-methods request-method) (name request-method) "other")
                                  :route (or @route "unmatched")}]
                      (metrics/inc! requests (assoc labels :status (str status)))
                      (metrics/observe! durations labels (/ (- (System/nanoTime) start) 1e9))))]
      (try
        (let [response (handler (assoc request ::route route))]
          (record! (:status response))
          response)
        (catch Throwable t
          (record! 500)
          (throw t))))))

(defn wrap-signed-out
  "A form sent without JavaScript after its session ended gets a page saying
  so, with a way back through sign-in, rather than the bare 401 fetches
  get."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (and (= 401 (:status response)) (not (response/datastar? request)))
        (let [back (auth/local-path (referer-path request))]
          (layout/message request 401 (i18n/t "Signed out")
                          (i18n/t "You were signed out, so this was not saved. Sign in, then send it again.")
                          [:a.button {:href (str "/login?" (codec/form-encode {"return_to" back}))} (i18n/t "Sign in")]))
        response))))

(defn wrap-language
  "Speaks the community's language, `catalog` (nil for English), while
  handling the request."
  [handler catalog]
  (if catalog
    (fn [request]
      (binding [i18n/*catalog* catalog
                i18n/*community-catalog* catalog]
        (handler request)))
    handler))

(defn wrap-timezone
  "Shows times in the community's zone while handling the request."
  [handler zone]
  (if zone
    (fn [request]
      (binding [dates/*zone* zone]
        (handler request)))
    handler))

(defn handler
  "The application: every context's routes behind the shared middleware.
  `ctx` holds the components and `:settings` handlers use; the handler adds
  `:sign-in-requests` and `:login-requests`, the recent sign-in link
  requests and sign-ins started, by client."
  [ctx]
  (let [{:keys [settings] :as ctx} (assoc ctx :sign-in-requests (atom {}) :login-requests (atom {}))]
    (-> (dispatcher (merge health/routes assets/routes account/routes theme/routes home/routes listing/routes
                           message/routes moderation/routes branding/routes privacy/routes manifest/routes metrics-page/routes
                           push/routes) ctx)
        (wrap-signed-out)
        (auth/wrap-same-origin (:base-url settings))
        (wrap-params)
        (wrap-body-limit)
        (error/wrap-errors)
        ;; Outside the error pages, so they find the session and keep its header.
        (wrap-cookies)
        (palette/wrap-palette)
        (wrap-ctx ctx)
        (wrap-timezone (:timezone settings))
        (wrap-language (:catalog settings))
        (security/wrap-security-headers settings)
        (wrap-client-address (:behind-proxy settings))
        (wrap-metrics))))
