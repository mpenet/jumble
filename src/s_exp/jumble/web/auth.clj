(ns s-exp.jumble.web.auth
  "Who is signed in: session cookies, routes for members only, local return
  paths, and the same-origin check on requests that change things."
  (:require [clojure.string :as str]
            [ring.util.codec :as codec]
            [s-exp.jumble.account.membership :as membership]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.message.model :as message]
            [s-exp.jumble.moderation.model :as moderation]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.web.response :as response]))

(set! *warn-on-reflection* true)

(def session-cookie "jumble_session")

(defn- secure?
  [^String base-url]
  (str/starts-with? base-url "https://"))

(defn cookie-name
  "`name`, with the __Host- prefix when served over https: browsers then only
  accept the cookie if it is Secure, host-only and scoped to /."
  [base-url name]
  (if (secure? base-url) (str "__Host-" name) name))

(defn session-token
  "The session token `request` carries, if any."
  [{:keys [ctx cookies]}]
  (get-in cookies [(cookie-name (get-in ctx [:settings :base-url] "") session-cookie) :value]))

(defn cookie
  "Ring cookie map for `value`. Lax rather than Strict: the provider's
  redirect back to us is a cross-site navigation that must carry it."
  [base-url value max-age-seconds]
  {:value value
   :path "/"
   :http-only true
   :secure (secure? base-url)
   :same-site :lax
   :max-age max-age-seconds})

(defn local-path
  "`target` when it is a path on this site, else \"/\". Only printable
  ASCII without backslashes, which also fits in a Location header; rejects
  scheme-relative (//host) forms browsers resolve to other hosts."
  [target]
  (if (and (string? target)
           (re-matches #"/(?!/)[\x21-\x5B\x5D-\x7E]*" target))
    target
    "/"))

(defn request-path
  "Path and query string of `request`, to come back to."
  [{:keys [uri query-string]}]
  (cond-> (or uri "/") query-string (str "?" query-string)))

(defn- login-redirect
  [request]
  (response/redirect 302 (str "/login?" (codec/form-encode {"return_to" (request-path request)}))))

(defn- page-load?
  [{:keys [request-method] :as request}]
  (and (= :get request-method) (not (response/datastar? request))))

(defn require-user
  "Wraps `handler` so it only runs for a signed-in user, assoc'd as `:user`,
  with `:owner` true for the community's owner, and `:moderator` for the
  owner and the moderators the owner appointed.
  Anonymous page loads are redirected to login; anything else gets 401, as a
  redirect would be followed by fetch and return the login page as data.
  The handler speaks the member's language (see [[i18n/speaking]]).
  When the session is renewed its cookie is sent again with a fresh Max-Age,
  so the browser keeps it as long as the server does. Someone no longer
  allowed to sign in (see [[account/allowed-emails]]) is signed out at once."
  [handler]
  (fn [{:keys [ctx] :as request}]
    (let [{:keys [db settings]} ctx
          {:keys [base-url session-ttl]} settings
          session-name (cookie-name base-url session-cookie)
          t (session-token request)
          resumed (account/resume-session! db t (System/currentTimeMillis) session-ttl)
          member (when resumed
                   (or (membership/allows? (account/allowed-emails db (:owner settings))
                                           (get-in resumed [:user :email]))
                       (do (account/delete-session! db t) false)))]
      (if-let [{:keys [user renewed]} (when member resumed)]
        (cond-> (i18n/speaking (:language user)
                  (handler (let [owner (= (:owner settings) (str/lower-case (:email user)))
                                 moderator (or owner (account/moderator? db (:email user)))]
                             (cond-> (assoc request
                                            :user user
                                            :owner owner
                                            :moderator moderator
                                          ;; For the header; read when the page is rendered, after
                                          ;; the handler has, say, marked a thread read.
                                            :unread-messages (delay (message/unread-count db (:id user)))
                                            :changed-favorites (delay (listing/changed-favorites db (:id user))))
                               moderator (assoc :open-reports (delay (moderation/open-count db)))))))
          renewed (update :cookies #(merge {session-name (cookie base-url t (quot session-ttl 1000))} %)))
        (if (page-load? request)
          (login-redirect request)
          (response/text 401 "Sign in required"))))))

(defn known-user
  "`request` with its signed-in `:user` when there is one, for pages any
  route can end on (not found, errors) to keep their header. Reads only:
  routes needing a member use [[require-user]]."
  [{:keys [ctx] :as request}]
  (let [{:keys [db settings]} ctx
        base-url (:base-url settings)
        ;; An error page must not fail on account of this.
        user (when (and db base-url)
               (try
                 (account/session-user db (session-token request) (System/currentTimeMillis))
                 (catch Exception _
                   nil)))]
    (cond-> request user (assoc :user user))))

(defn in-known-language
  "`(f request)`, `request` with its signed-in `:user` when there is one (see
  [[known-user]]), in that member's language: for pages any route can end
  on, written after the member is known."
  [request f]
  (let [request (known-user request)]
    (i18n/speaking (get-in request [:user :language]) (f request))))

(defn wrap-same-origin
  "Refuses state-changing requests whose Origin is not `base-url`, which the
  configuration writes as browsers send origins. Browsers send Origin on
  every cross-origin and same-origin POST, so a missing one is refused too. Cookies being SameSite=Lax already stop most cross-site writes;
  this covers what Lax does not, such as sibling subdomains."
  [handler base-url]
  (fn [{:keys [request-method headers] :as request}]
    (if (or (#{:get :head :options} request-method)
            (= base-url (get headers "origin")))
      (handler request)
      (response/text 403 "Cross-origin request refused"))))
