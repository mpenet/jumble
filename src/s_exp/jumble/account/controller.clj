(ns s-exp.jumble.account.controller
  "Signing in, by the community's provider or an emailed link, signing out,
  and a member's own settings, data export and account deletion."
  (:require [s-exp.jumble.metrics :as metrics]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [s-exp.ex :as ex]
            [s-exp.jumble.account.data :as data]
            [s-exp.jumble.account.join :as join]
            [s-exp.jumble.account.membership :as membership]
            [s-exp.jumble.account.email :as email]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.web.params :as params]
            [s-exp.jumble.account.view :as view]
            [s-exp.jumble.web.auth :as auth]
            [s-exp.jumble.view.dates :as dates]
            [s-exp.jumble.error :as error]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.view.layout :as layout]
            [s-exp.jumble.email.mail :as mail]
            [s-exp.jumble.oidc :as oidc]
            [s-exp.jumble.push.crypto :as crypto]
            [s-exp.jumble.push.model :as push]
            [s-exp.jumble.web.response :as response]
            [s-exp.jumble.token :as token]
            [s-exp.oda :as oda])
  (:import (java.net InetAddress)
           (java.time Instant LocalDate)))

(set! *warn-on-reflection* true)

(def ^:private login-cookie "jumble_login")
(def ^:private login-ttl (* 10 60 1000))

(defn- sign-in-again
  []
  [:a.button {:href "/login"} (i18n/t "Sign in again")])

(defn- unavailable
  [request]
  (layout/message request
                  503
                  (i18n/t "Sign-in unavailable")
                  (i18n/t "The sign-in service cannot be reached right now. Please try again shortly.")
                  [:a.button {:href "/login"} (i18n/t "Try again")]))

(defn- email-domain
  "The domain of `email`, all that is logged of an address."
  [^String email]
  (let [at (.lastIndexOf email "@")]
    (if (neg? at) "(no domain)" (str/lower-case (subs email (inc at))))))

(defn- allowed?
  "Whether the identity in `claims` may sign in: `allowed-emails` lets its
  address in and, when `require-hosted-domain`, the organisation managing
  the account (the provider's hd claim) is the address's own domain. An
  address let in by itself alone (a guest a moderator added, say) needs no
  organisation: it was named, not inferred from a domain."
  [allowed-emails require-hosted-domain {:keys [email hosted-domain]}]
  (and (membership/allows? allowed-emails email)
       (or (not require-hosted-domain)
           (membership/only-by-address? allowed-emails email)
           (and (string? hosted-domain) (= (str/lower-case hosted-domain) (email-domain email))))))

(def ^:private link-minutes
  "How long an emailed sign-in link works."
  15)

(def ^:private links-per-client
  "Sign-in links one client may ask for while a link works, whatever the
  addresses: a bound on the email one machine can make Jumble send."
  5)

(def ^:private logins-per-client
  "Sign-ins one client may start while one can be completed: each is
  recorded until then."
  20)

(defn- client-key
  "Who `remote-addr` counts as: the address, or for IPv6 its /64, which one
  holder usually has whole."
  [^String remote-addr]
  (let [bs (.getAddress (InetAddress/getByName remote-addr))]
    (if (= 16 (alength bs))
      (apply str (map #(format "%02x" %) (take 8 bs)))
      remote-addr)))

(defn- client-allowed!
  "Counts a request by `remote-addr` at `now` in `requests` (an atom of
  client to request times), unless it already made `limit` in the last
  `window` ms. True when allowed."
  [requests remote-addr now window limit]
  (let [client (client-key remote-addr)
        since (- now window)
        recent (fn [m] (into {} (keep (fn [[k ts]] (when-let [ts (seq (filter #(> % since) ts))] [k (vec ts)]))) m))
        [before after] (swap-vals! requests
                                   (fn [m]
                                     (let [m (recent m)]
                                       (cond-> m
                                         (< (count (get m client)) limit)
                                         (update client (fnil conj []) now)))))]
    (> (count (get after client)) (count (get (recent before) client)))))

(defn- email-sign-in?
  [request]
  (= "email" (get-in request [:ctx :settings :sign-in])))

(defn- joining-token
  "`t` when it is the join link's, for a sign-in to carry; nil otherwise."
  [db t]
  (when (and (seq t) (join/valid? db t (System/currentTimeMillis)))
    t))

(defn- joining?
  "Whether `email`, which no rule lets in, joins by the join link's
  `join-token`, still the link's: the moderators' removals still apply."
  [db join-token email]
  (and (some? (joining-token db join-token))
       (not (account/removed? db email))))

(defn- oidc-login
  [{:keys [ctx params remote-addr] :as request}]
  (let [{:keys [db oidc settings login-requests]} ctx
        {:keys [base-url]} settings
        nonce (token/generate)
        code-verifier (token/generate)
        target (auth/local-path (params/one params "return_to"))]
    (if (and (:clients-known settings)
             (not (client-allowed! login-requests remote-addr (System/currentTimeMillis) login-ttl logins-per-client)))
      (layout/message request
                      429
                      (i18n/t "Too many sign-ins")
                      (i18n/t "Too many sign-ins were started from here. Please try again in a few minutes.")
                      [:a.button {:href "/login"} (i18n/t "Try again")])
      (try
        (let [state (token/generate)
              ;; Built first: when the provider is unreachable this throws and
              ;; no attempt is recorded.
              url (oidc/authorization-url oidc {:state state :nonce nonce :code-verifier code-verifier})]
          (account/create-login-attempt! db
                                         state
                                         {:nonce nonce
                                          :code-verifier code-verifier
                                          :return-to target
                                          :join-token (joining-token db (params/one params "join"))}
                                         (System/currentTimeMillis)
                                         login-ttl)
          (-> (response/redirect 302 url)
              (assoc :cookies {(auth/cookie-name base-url login-cookie)
                               (auth/cookie base-url state (quot login-ttl 1000))})))
        (catch clojure.lang.ExceptionInfo e
          (if (ex/type? e ::ex/unavailable)
            (do (log/warn e "Sign-in unavailable")
                (unavailable request))
            (throw e)))))))

(defn- email-login
  [{:keys [params query-string] :as request}]
  (if (= "sent" query-string)
    (response/html 200 (view/check-inbox request link-minutes))
    (response/html 200 (view/email-sign-in request {:values {:return-to (auth/local-path (params/one params "return_to"))}}))))

(defn login
  "Sends people to the sign-in provider, or asks for their email address."
  [request]
  (if (email-sign-in? request)
    (email-login request)
    (oidc-login request)))

(defn- clear-cookie
  [base-url name]
  {(auth/cookie-name base-url name) (auth/cookie base-url "" 0)})

(def ^:private sign-ins
  (metrics/counter "jumble_sign_ins_total" "Sessions opened by signing in, by the community's sign-in method."))

(defn- start-session
  "Opens a session for `user` and sends them to `target`; counted."
  [{:keys [ctx]} user target]
  (metrics/inc! sign-ins {:method (name (get-in ctx [:settings :sign-in]))})
  (let [{:keys [db settings]} ctx
        {:keys [base-url session-ttl]} settings
        now (System/currentTimeMillis)
        _ (account/delete-expired-sessions! db now)
        t (account/create-session! db (:id user) now session-ttl)]
    (-> (response/redirect target)
        (assoc :cookies {(auth/cookie-name base-url auth/session-cookie)
                         (auth/cookie base-url t (quot session-ttl 1000))}))))

(defn- not-allowed
  [request]
  (layout/message request
                  403
                  (i18n/t "Account not allowed")
                  (i18n/t "Only members of this community can sign in here.")
                  (sign-in-again)))

(defn- community-full
  [request]
  (layout/message request
                  403
                  (i18n/t "This community is full")
                  (i18n/t "It has as many members as it can have. Ask its moderators to make room.")))

(defn- room
  "Account creation options for `email`: the community's member limit, which
  never keeps its moderators out."
  [settings email]
  {:member-limit (when-not (contains? (:moderators settings) (str/lower-case email))
                   (:member-limit settings))})

(defn- finish-deletion!
  "Finishes deleting the account `iss` and `sub` when its deletion stopped
  part way, so that signing in starts a new account (see
  [[account/deleted-account]])."
  [{:keys [db blobs]} iss sub]
  (when-let [id (account/deleted-account db iss sub)]
    (log/info "Finishing the deletion of account" id)
    (data/delete-account! db blobs id (System/currentTimeMillis))))

(defn- sign-in!
  "Verifies the provider's answer for `attempt` and opens a session, or
  returns the response explaining why not."
  [{:keys [ctx] :as request} attempt code]
  (let [{:keys [db oidc settings]} ctx
        {:keys [require-hosted-domain]} settings
        allowed-emails (account/allowed-emails db (:allowed-emails settings))
        claims (oidc/verify-id-token oidc
                                     (oidc/exchange-code! oidc code (:code-verifier attempt))
                                     (:nonce attempt))
        allowed (and (allowed? allowed-emails require-hosted-domain claims)
                     (not (account/removed? db (:email claims))))
        joining (and (not allowed) (joining? db (:join-token attempt) (:email claims)))]
    (if-not (or allowed joining)
      (do (log/info "Sign-in refused, not allowed:" (email-domain (:email claims)))
          (not-allowed request))
      (do (finish-deletion! ctx (:iss claims) (:sub claims))
          (let [user (account/upsert-user! db claims (System/currentTimeMillis) (room settings (:email claims)))]
            (when joining
              (log/info "Joined by the join link:" (email-domain (:email claims)))
              (join/admit! db (:email claims) (System/currentTimeMillis)))
            (start-session request user (:return-to attempt)))))))

(defn request-link
  "Emails a sign-in link to an address allowed in. Everyone is told the
  same, so the page reveals nothing about who may sign in."
  [{:keys [ctx params remote-addr] :as request}]
  (if-not (email-sign-in? request)
    (layout/not-found request)
    (let [{:keys [db settings sign-in-requests]} ctx
          email (some-> (params/one params "email") str/trim str/lower-case)
          target (auth/local-path (params/one params "return_to"))
          join-token (params/one params "join")]
      (if-not (mail/plain-address? email)
        (response/html 422
                       (view/email-sign-in request
                                           {:values {:email (params/one params "email") :return-to target :join join-token}
                                            :errors {:email (if (empty? email)
                                                              (i18n/t "Enter your email address.")
                                                              (i18n/t "“{email}” is not an email address." {:email email}))}}))
        (do (when (or ((account/allowed db (:allowed-emails settings)) email)
                      (joining? db join-token email))
              (if (and (:clients-known settings)
                       (not (client-allowed! sign-in-requests
                                             remote-addr
                                             (System/currentTimeMillis)
                                             (* link-minutes 60 1000)
                                             links-per-client)))
                (log/info "Sign-in link not sent, too many asked for by one client")
                (let [outcome (account/request-email-sign-in! db
                                                              {:email email
                                                               :return-to target
                                                               :join-token (joining-token db join-token)
                                                               :now (System/currentTimeMillis)
                                                               :ttl (* link-minutes 60 1000)
                                                               :email-for (let [language (account/language-of db email)]
                                                                            #(i18n/speaking language (email/sign-in-link settings email % link-minutes)))})]
                  (when (= :limited outcome)
                    (log/info "Sign-in link not sent, too many asked for:" (email-domain email))))))
            ;; Redirected, so reloading the page sends no other link.
            (response/redirect "/login?sent"))))))

(defn- link-expired
  [request]
  (layout/message request
                  400
                  (i18n/t "Link expired")
                  (i18n/t "This sign-in link has expired or was already used.")
                  [:a.button {:href "/login"} (i18n/t "Get a new link")]))

(defn confirm-link
  "The page a sign-in link opens, asking to confirm."
  [{:keys [ctx params] :as request}]
  (let [t (params/one params "token")]
    (cond
      (not (email-sign-in? request)) (layout/not-found request)

      :else
      (if-let [email (account/email-sign-in (:db ctx) t (System/currentTimeMillis))]
        (response/html 200 (view/confirm-email-sign-in request email t))
        (link-expired request)))))

(defn use-link
  "Signs in with a link's token, once, if its address is still allowed."
  [{:keys [ctx params] :as request}]
  (let [{:keys [db settings]} ctx]
    (if-not (email-sign-in? request)
      (layout/not-found request)
      (if-let [{:keys [email return-to join-token]}
               (account/use-email-sign-in! db (params/one params "token") (System/currentTimeMillis))]
        (let [allowed ((account/allowed db (:allowed-emails settings)) email)
              joining (and (not allowed) (joining? db join-token email))]
          (if (or allowed joining)
            (ex/try+
              (finish-deletion! ctx "email" email)
              (let [user (account/email-user! db email (System/currentTimeMillis) (room settings email))]
                (when joining
                  (log/info "Joined by the join link:" (email-domain email))
                  (join/admit! db email (System/currentTimeMillis)))
                (start-session request user return-to))
              (catch ::error/community-full _
                (log/info "Sign-in refused, community full:" (email-domain email))
                (community-full request)))
            (do (log/info "Sign-in refused, not allowed:" (email-domain email))
                (not-allowed request))))
        (link-expired request)))))

(defn join-page
  "The join link's page, while it is the community's: an invitation, and
  the way in."
  [{:keys [ctx path-params] :as request}]
  (let [t (:token path-params)]
    (if (some? (joining-token (:db ctx) t))
      (response/html 200 (view/join request t (email-sign-in? request)))
      (layout/not-found request (i18n/t "This join link no longer works. Ask the community's moderators for the current one.")))))

(defn callback
  [{:keys [ctx params cookies] :as request}]
  (let [{:keys [db settings]} ctx
        [code state error] (map #(params/one params %) ["code" "state" "error"])
        browser-state (get-in cookies [(auth/cookie-name (:base-url settings) login-cookie) :value])
        ;; Consumed whatever happens next, so a callback works at most once.
        attempt (when (token/same? state browser-state)
                  (account/consume-login-attempt! db state (System/currentTimeMillis)))]
    ;; The attempt is spent whatever the outcome; its cookie goes with it.
    (update (cond
              (nil? attempt)
              (layout/message request
                              400
                              (i18n/t "Sign-in expired")
                              (i18n/t "This sign-in is invalid or has expired.")
                              (sign-in-again))

              error
              (do (log/info "Sign-in refused by the provider:" (pr-str error))
                  (layout/message request
                                  403
                                  (i18n/t "Sign-in refused")
                                  (i18n/t "The sign-in service refused the request.")
                                  (sign-in-again)))

              :else
              (try
                (sign-in! request attempt code)
                (catch clojure.lang.ExceptionInfo e
                  (let [t (ex/ex-type e)]
                    (log/warn e "Sign-in failed:" t)
                    (case t
                      ::error/community-full
                      (community-full request)

                      ::error/email-not-verified
                      (layout/message request
                                      403
                                      (i18n/t "Email not verified")
                                      (i18n/t "The sign-in service has not verified your email address.")
                                      (sign-in-again))

                      (::error/provider-unreachable ::error/discovery-failed ::error/keys-unavailable)
                      (unavailable request)

                      (layout/message request
                                      400
                                      (i18n/t "Sign-in failed")
                                      (i18n/t "Something went wrong while signing you in.")
                                      (sign-in-again)))))))
            :cookies
            merge
            (clear-cookie (:base-url settings) login-cookie))))

(defn logout
  [{:keys [ctx] :as request}]
  (let [{:keys [db settings]} ctx
        {:keys [base-url]} settings]
    (account/delete-session! db (auth/session-token request))
    (-> (response/redirect "/")
        (assoc :cookies (clear-cookie base-url auth/session-cookie)))))

(defn settings
  "Settings. People signing in by email set their name here; a sign-in
  provider supplies it otherwise."
  [{:keys [ctx user query-string] :as request}]
  (response/html 200
                 (view/settings request
                                {:devices (push/devices (:db ctx) (:id user))
                                 :application-key (crypto/application-key (push/keys! (:db ctx)))
                                 :notify-messages (account/notify-messages (:db ctx) (:id user))
                                 :weekly-digest (account/weekly-digest (:db ctx) (:id user))
                                 :language (:language user)
                                 :name-editable (email-sign-in? request)
                                 :values {:name (:name user)}
                                 :saved (= "saved" query-string)})))

(defn save-settings
  "Saves the settings form. An unchecked box is absent from the form."
  [{:keys [ctx user params] :as request}]
  (let [db (:db ctx)
        notify (= "on" (params/one params "notify_messages"))
        weekly (= "on" (params/one params "weekly_digest"))
        {:keys [errors name]} (when (email-sign-in? request) (account/validate-name (params/one params "name")))]
    (if errors
      (response/html 422
                     (view/settings request
                                    {:devices (push/devices db (:id user))
                                     :application-key (crypto/application-key (push/keys! db))
                                     :notify-messages notify :weekly-digest weekly :language (:language user)
                                     :name-editable true
                                     :values {:name (params/one params "name")} :errors errors}))
      (do (when name
            (account/set-name! db (:id user) name))
          (account/set-notify-messages! db (:id user) notify)
          (account/set-weekly-digest! db (:id user) weekly)
          ;; A language Jumble does not speak is the community's.
          (account/set-language! db (:id user) (i18n/languages (params/one params "language")))
          (response/redirect "/settings?saved")))))

(defn export-data
  "Everything Jumble holds about the user, as a JSON file to download."
  [{:keys [ctx user]}]
  (let [now (Instant/now)
        today (LocalDate/ofInstant now dates/*zone*)]
    {:status 200
     :headers {"content-type" "application/json; charset=utf-8"
               "content-disposition" (str "attachment; filename=\"jumble-data-" today ".json\"")
               "cache-control" "no-store"}
     :body (oda/write-str (data/export (:db ctx) (:blobs ctx) (:id user) now))}))

(defn confirm-delete
  [request]
  (response/html 200 (view/confirm-delete request)))

(defn delete-account
  "Deletes the user's account and signs them out."
  [{:keys [ctx user] :as request}]
  (let [{:keys [db blobs settings]} ctx]
    (data/delete-account! db blobs (:id user) (System/currentTimeMillis))
    (-> (layout/message (dissoc request :user)
                        200
                        (i18n/t "Your account was deleted")
                        (i18n/t "Your listings, photos and favorites are gone. Messages you sent stay with the people you wrote to, from \"A former member\".")
                        [:a.button {:href "/"} (i18n/t "Done")])
        (assoc :cookies (clear-cookie (:base-url settings) auth/session-cookie)))))

(def routes
  {[:get "/join/{token}"] #'join-page
   [:get "/login"] #'login
   [:post "/login"] #'request-link
   [:get "/login/email"] #'confirm-link
   [:post "/login/email"] #'use-link
   [:get "/settings"] (auth/require-user #'settings)
   [:post "/settings"] (auth/require-user #'save-settings)
   [:get "/settings/export"] (auth/require-user #'export-data)
   [:get "/settings/delete"] (auth/require-user #'confirm-delete)
   [:post "/settings/delete"] (auth/require-user #'delete-account)
   [:get "/auth/callback"] #'callback
   [:post "/logout"] #'logout})
