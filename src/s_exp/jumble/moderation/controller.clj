(ns s-exp.jumble.moderation.controller
  "Moderation on the web: reports, removing or dismissing, who may sign in
  and removing members, the lists of categories and places, and the
  community's palette."
  (:require [clojure.string :as str]
            [s-exp.ex :as ex]
            [s-exp.jumble.account.data :as data]
            [s-exp.jumble.account.email :as account-email]
            [s-exp.jumble.account.membership :as membership]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.account.invite :as invite]
            [s-exp.jumble.account.join :as join]
            [s-exp.jumble.web.auth :as auth]
            [s-exp.jumble.error :as error]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.view.layout :as layout]
            [s-exp.jumble.listing.choices :as choices]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.metrics :as metrics]
            [s-exp.jumble.moderation.email :as email]
            [s-exp.jumble.moderation.model :as moderation]
            [s-exp.jumble.moderation.view :as view]
            [s-exp.jumble.page.palette :as palette]
            [s-exp.jumble.web.params :as params]
            [s-exp.jumble.web.response :as response]))

(set! *warn-on-reflection* true)

(defn- moderators-only
  "Like [[auth/require-user]], for moderators only: anyone else is told there is
  nothing here."
  [handler]
  (auth/require-user (fn [request]
                       (if (:moderator request)
                         (handler request)
                         (layout/not-found request)))))

(defn- listing-in-path
  [{:keys [ctx path-params]}]
  (some->> (params/id (:id path-params)) (listing/by-id (:db ctx))))

(defn- moderated-listing
  "The listing in the path when the moderator may remove it: someone
  else's. Their own they remove as owners."
  [{:keys [user] :as request}]
  (when-let [found (listing-in-path request)]
    (when (not= (:user-id found) (:id user))
      found)))

(defn report
  "Reports the listing in the path, for someone other than its owner."
  [{:keys [ctx params user] :as request}]
  (let [{:keys [db settings]} ctx
        {:keys [id title] :as found} (listing-in-path request)]
    (if-not found
      (layout/not-found request)
      (let [page (str "/listings/" id)
            {:keys [errors reason]} (moderation/validate-reason (params/one params "reason"))]
        (if errors
          (response/html 422
                         (view/report-form request
                                           found
                                           {:values {:reason (params/one params "reason")}
                                            :errors errors}))
          (if (moderation/report! db
                                  {:listing-id id :reporter-id (:id user) :reason reason
                                   :now (System/currentTimeMillis)
                                   :alerts (email/alerts settings
                                                         {:title title :reporter-name (:name user)
                                                          :reason reason}
                                                         #(account/language-of db %))})
            (response/redirect (str page "?reported"))
            (layout/not-found request)))))))

(defn reports
  [{:keys [ctx] :as request}]
  (response/html 200 (view/reports request (moderation/open-reports (:db ctx)))))

(defn confirm-removal
  "Asks a moderator to confirm removing the listing in the path, with a
  reason for its owner."
  [request]
  (if-let [found (moderated-listing request)]
    (response/html 200 (view/confirm-removal request found {}))
    (layout/not-found request)))

(defn remove-listing
  "Removes the listing in the path, emailing its owner the reason given."
  [{:keys [ctx params user] :as request}]
  (let [{:keys [db blobs settings]} ctx
        {:keys [id title owner-email] :as found} (moderated-listing request)
        {:keys [errors reason]} (moderation/validate-reason (params/one params "reason"))]
    (cond
      (nil? found) (layout/not-found request)

      errors
      (response/html 422
                     (view/confirm-removal request
                                           found
                                           {:values {:reason (params/one params "reason")}
                                            :errors errors}))

      :else
      (do (moderation/remove-listing! db
                                      blobs
                                      {:listing-id id :moderator-id (:id user)
                                       :now (System/currentTimeMillis)
                                       :reason reason
                                       :notice (i18n/speaking (account/language-of db owner-email)
                                                 (email/removal-notice settings
                                                                       {:owner-email owner-email
                                                                        :title title
                                                                        :reason reason}))})
          (metrics/inc! listing/closed {:outcome "removed"})
          (response/redirect "/manage")))))

(defn dismiss
  "Dismisses the reports on the listing in the path, someone else's (see
  [[moderated-listing]])."
  [{:keys [ctx user] :as request}]
  (if-let [{:keys [id]} (moderated-listing request)]
    (do (moderation/dismiss! (:db ctx) {:listing-id id :moderator-id (:id user) :now (System/currentTimeMillis)})
        (response/redirect "/manage"))
    (layout/not-found request)))

(defn- members-page
  "The members page answering with `status`, with `form` (see
  [[view/members]]) for what was typed."
  [{:keys [ctx] :as request} status form]
  (let [{:keys [db settings]} ctx
        configured (:allowed-emails settings)]
    (response/html status
                   (view/members request
                                 (assoc form
                                        :configured configured
                                        :added (when-not (= :anyone configured) (account/added-emails db))
                                        :join (join/current db (System/currentTimeMillis))
                                        :invitations-open (invite/open? db))))))

(def ^:private join-expiries
  "How long a join link may work, as the form offers it: never expiring
  unless chosen."
  {"30" (* 30 24 60 60 1000)
   "7" (* 7 24 60 60 1000)})

(defn create-join-link
  "Makes the join link, in place of any earlier one."
  [{:keys [ctx params user]}]
  (join/create! (:db ctx)
                {:moderator-id (:id user)
                 :expires-in (get join-expiries (params/one params "expires"))
                 :now (System/currentTimeMillis)})
  (response/redirect "/manage/members"))

(defn turn-off-join-link
  [{:keys [ctx]}]
  (join/turn-off! (:db ctx))
  (response/redirect "/manage/members"))

(defn set-member-invitations
  "Lets members invite people, or stops them."
  [{:keys [ctx params]}]
  (invite/set-open! (:db ctx) (= "on" (params/one params "open")))
  (response/redirect "/manage/members"))

(defn members
  [{:keys [query-string] :as request}]
  (members-page request
                200
                {:saved (= "added" query-string)
                 :removed (= "removed" query-string)}))

(defn- parse-patterns
  "`{:patterns [...]}` from text typed one per line or comma separated, or
  `{:error message}`."
  [text]
  (ex/try+
    (let [patterns (membership/parse (str/split (or text "") #"[,\s]+"))]
      (if (= :anyone patterns)
        {:error (i18n/t "To let anyone in, use * in the configuration.")}
        {:patterns patterns}))
    (catch ::error/invalid-pattern _
      {:error (ex-message &ex)})))

(defn add-members
  "Lets the typed patterns sign in, inviting the plain addresses when asked."
  [{:keys [ctx params user] :as request}]
  (let [{:keys [db settings]} ctx
        configured (:allowed-emails settings)
        invite (= "on" (params/one params "invite"))
        {:keys [patterns error]} (parse-patterns (params/one params "patterns"))]
    (cond
      (= :anyone configured) (response/redirect "/manage/members")

      error
      (members-page request
                    422
                    {:values {:patterns (params/one params "patterns") :invite invite}
                     :errors {:patterns error}})

      :else
      (do (account/allow! db
                          {:patterns patterns :moderator-id (:id user) :now (System/currentTimeMillis)
                           :invite-for (when invite
                                         (fn [address]
                                           ;; Not a member yet: the community's language.
                                           (when-not (str/includes? address "*")
                                             (i18n/speaking nil
                                               (account-email/invitation settings (:name user) address)))))})
          (response/redirect "/manage/members?added")))))

(defn withdraw-pattern
  "No longer lets a pattern sign in: those only it let in are signed out at
  their next request."
  [{:keys [ctx params]}]
  (account/disallow! (:db ctx) (params/one params "pattern"))
  (response/redirect "/manage/members"))

(defn- removal
  "The member the address typed in `params` names, to remove: `{:email
  :accounts}`, or `{:error message}` for no member or a moderator, who is
  one by the configuration."
  [{:keys [ctx params]}]
  (let [{:keys [db settings]} ctx
        email (some-> (params/one params "email") str/trim str/lower-case)
        accounts (when (seq email) (account/members-with-email db email))]
    (cond
      ;; By mailbox, as removal goes: Dave+x@ is dave@, a moderator.
      (some (fn [moderator] (some #(= (account/mailbox moderator) (account/mailbox %))
                                  (cons email (map :email accounts))))
            (:moderators settings))
      {:error (i18n/t "{email} is a moderator, by the configuration: remove them there." {:email email})}

      (empty? accounts) {:error (i18n/t "No member has this address.")}
      :else {:email email :accounts accounts})))

(defn- removal-refused
  [{:keys [params] :as request} error]
  (members-page request 422 {:values {:email (params/one params "email")} :errors {:email error}}))

(defn confirm-member-removal
  "Asks a moderator to confirm removing a member, saying who and what goes."
  [request]
  (let [{:keys [error email accounts]} (removal request)]
    (if error
      (removal-refused request error)
      (response/html 200
                     (view/confirm-member-removal request
                                                  {:email email
                                                   :name (:name (first accounts))
                                                   :listings (reduce + (map :listings accounts))})))))

(defn remove-member
  "Removes a member: their account goes, as when they delete it, and they
  never sign in again (see [[data/remove-member!]])."
  [{:keys [ctx user] :as request}]
  (let [{:keys [error email]} (removal request)]
    (if error
      (removal-refused request error)
      (do (data/remove-member! (:db ctx)
                               (:blobs ctx)
                               {:email email :moderator-id (:id user)
                                :now (System/currentTimeMillis)})
          (response/redirect "/manage/members?removed")))))

(def ^:private lists
  "Which list of choices, by the path of its page."
  {"categories" :category "places" :place})

(defn- page-of
  [which]
  (str "/manage/" (some (fn [[path w]] (when (= which w) path)) lists)))

(defn- choices-page
  "The page of `which` list, with `form`: what was typed (`:values`) and
  what is wrong with it (`:errors`), by entry id, or `:add` for the new one."
  [{:keys [ctx] :as request} which status form]
  (let [db (:db ctx)]
    (response/html status
                   (view/choices request
                                 which
                                 (assoc form
                                        :page (page-of which)
                                        :entries (get (choices/all db) (if (= :category which) :categories :places))
                                        :counts (choices/listing-counts db which))))))

(defn- show-choices
  [request which]
  (choices-page request which 200 {}))

(defn- add-choice
  [{:keys [ctx params] :as request} which]
  (let [label (params/one params "label")
        {:keys [errors]} (choices/add! (:db ctx) which label)]
    (if errors
      (choices-page request which 422 {:values {:add label} :errors {:add (:label errors)}})
      (response/redirect (page-of which)))))

(defn- change-choice
  "Applies `(change db which id)` to the entry in the path: back to the page
  when done, with the problem when refused, not found when there is no
  such entry. `label` is what was typed, if anything."
  [{:keys [ctx path-params] :as request} which change label]
  (let [id (:id path-params)
        result (change (:db ctx) which id)]
    (cond
      (:ok result) (response/redirect (page-of which))
      (:errors result) (choices-page request which 422 {:values {id label} :errors {id (get-in result [:errors :label])}})
      :else (layout/not-found request))))

(defn- rename-choice
  [{:keys [params] :as request} which]
  (let [label (params/one params "label")]
    (change-choice request which #(choices/rename! %1 %2 %3 label) label)))

(defn- move-choice
  [{:keys [ctx params path-params]} which]
  (when-let [direction ({"up" :up "down" :down} (params/one params "direction"))]
    (choices/move! (:db ctx) which (:id path-params) direction))
  (response/redirect (page-of which)))

(defn- retire-choice
  [request which]
  (change-choice request which #(choices/retire! %1 %2 %3 (System/currentTimeMillis)) nil))

(defn- restore-choice
  [request which]
  (change-choice request which choices/restore! nil))

(defn appearance
  [{:keys [ctx query-string] :as request}]
  (response/html 200
                 (view/appearance request
                                  {:chosen (palette/chosen (:db ctx))
                                   :saved (= "saved" query-string)})))

(defn choose-palette
  [{:keys [ctx params] :as request}]
  (let [chosen (params/one params "palette")]
    (if (some #{chosen} palette/names)
      (do (palette/choose! (:db ctx) chosen)
          (response/redirect "/manage/appearance?saved"))
      (response/html 400 (view/appearance request {:chosen (palette/chosen (:db ctx))})))))

(defn poster
  "The poster, in the community's language or the one asked for; its code
  opens the join link when moderators ask for it and one works."
  [{:keys [ctx params] :as request}]
  (let [{:keys [base-url allowed-emails]} (:settings ctx)
        language (i18n/languages (params/one params "language"))
        join (when-not (= :anyone allowed-emails) (join/current (:db ctx) (System/currentTimeMillis)))
        joining (boolean (and join (= "join" (params/one params "code"))))]
    (i18n/speaking language
      (response/html 200
                     (view/poster request
                                  {:url (if joining (str base-url "/join/" (:token join)) base-url)
                                   :address (-> base-url (str/replace #"^https?://" "") (str/replace #"/$" ""))
                                   :language language
                                   :join (some? join)
                                   :joining joining})))))

(defn- choices-routes
  "Moderators' pages managing each list of choices."
  []
  (into {}
        (mapcat (fn [[path which]]
                  (let [base (str "/manage/" path)
                        handle (fn [handler] (moderators-only #(handler % which)))]
                    {[:get base] (handle #'show-choices)
                     [:post base] (handle #'add-choice)
                     [:post (str base "/{id}/rename")] (handle #'rename-choice)
                     [:post (str base "/{id}/move")] (handle #'move-choice)
                     [:post (str base "/{id}/retire")] (handle #'retire-choice)
                     [:post (str base "/{id}/restore")] (handle #'restore-choice)})))
        lists))

(def routes
  (merge (choices-routes)
         {[:post "/listings/{id}/report"] (auth/require-user #'report)
          [:get "/manage"] (moderators-only #'reports)
          [:get "/manage/members"] (moderators-only #'members)
          [:post "/manage/members"] (moderators-only #'add-members)
          [:post "/manage/members/withdraw"] (moderators-only #'withdraw-pattern)
          [:post "/manage/members/join-link"] (moderators-only #'create-join-link)
          [:post "/manage/members/join-link/off"] (moderators-only #'turn-off-join-link)
          [:post "/manage/members/invitations"] (moderators-only #'set-member-invitations)
          [:get "/manage/members/remove"] (moderators-only #'confirm-member-removal)
          [:post "/manage/members/remove"] (moderators-only #'remove-member)
          [:get "/manage/listings/{id}/remove"] (moderators-only #'confirm-removal)
          [:post "/manage/listings/{id}/remove"] (moderators-only #'remove-listing)
          [:post "/manage/listings/{id}/dismiss"] (moderators-only #'dismiss)
          [:get "/manage/appearance"] (moderators-only #'appearance)
          [:get "/manage/poster"] (moderators-only #'poster)
          [:post "/manage/appearance"] (moderators-only #'choose-palette)}))
