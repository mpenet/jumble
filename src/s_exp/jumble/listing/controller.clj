(ns s-exp.jumble.listing.controller
  "Listings on the web: the board and search, posting, editing, statuses,
  favorites and saved searches."
  (:require [s-exp.jumble.metrics :as metrics]
            [clojure.string :as str]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.multipart-params.byte-array :refer [byte-array-store]]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.web.auth :as auth]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.view.layout :as layout]
            [s-exp.jumble.listing.choices :as choices]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.listing.saved-searches :as saved]
            [s-exp.jumble.listing.view :as view]
            [s-exp.jumble.web.params :as params]
            [s-exp.jumble.web.response :as response]
            [s-exp.jumble.money :as money])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def ^:private listings-created
  (metrics/counter "jumble_listings_created_total" "Listings posted."))

(def ^:private max-photo-bytes (* 10 1024 1024))

(defn wrap-choices
  "Adds the categories and places, as moderators left them, to the settings
  `handler` sees."
  [handler]
  (fn [{:keys [ctx] :as request}]
    (handler (update-in request [:ctx :settings] merge (choices/all (:db ctx))))))

(def ^:private for-members
  "Signed in, with the categories and places."
  (comp auth/require-user wrap-choices))

(defn mark-favorites
  "`listings` with `:favorite`, whether each is one of `user`'s favorites;
  left out of the user's own, which they cannot favorite."
  [db user listings]
  (let [others (remove #(= (:id user) (:user-id %)) listings)
        saved (listing/favorite-ids db (:id user) (map :id others))]
    (mapv (fn [{:keys [id user-id] :as found}]
            (cond-> found (not= (:id user) user-id) (assoc :favorite (contains? saved id))))
          listings)))

(defn new-form
  [{:keys [ctx] :as request}]
  (response/html 200 (view/form request {:values (listing/default-values (:settings ctx))})))

(defn- form-values
  [params]
  (into {} (map (fn [k] [k (params/one params (name k))]))
        [:kind :title :description :price :category :pickup :city]))

(defn- uploads
  "Bytes of the files in the photos field; the empty part browsers send for
  an untouched file input is dropped."
  [params]
  (let [photos (get params "photos")]
    (into []
          (comp (filter map?) (map :bytes) (filter #(pos? (alength ^bytes %))))
          (if (map? photos) [photos] photos))))

(defn- invalid-form
  "The form again with `errors`. `form` holds the rest of the form's state:
  values typed, whether files were dropped, and for edits the listing."
  [request form errors]
  (response/html 422 (view/form request (assoc form :errors errors))))

(defn create
  "Fields are validated before photos are processed: when the form comes
  back with errors the browser has dropped the files anyway."
  [{:keys [ctx params user] :as request}]
  (let [{:keys [db blobs settings]} ctx
        values (form-values params)
        files (uploads params)
        {:keys [listing errors]} (listing/validate settings values)]
    (if errors
      (invalid-form request {:values values :photos-dropped (seq files)} errors)
      (let [{:keys [photos errors]} (listing/prepare-photos files)]
        (if errors
          (invalid-form request {:values values :photos-dropped true} errors)
          (if-let [id (listing/create! db blobs (:id user) listing photos (System/currentTimeMillis))]
            (do (metrics/inc! listings-created {})
                (response/redirect (str "/listings/" id)))
            ;; The account was deleted meanwhile.
            (layout/not-found request)))))))

(def ^:private page-size 24)

(defn- search-terms
  "The query typed in the search box, when it has anything to search for."
  [q]
  (when (string? q)
    (let [q (str/trim q)
          q (subs q 0 (min 100 (count q)))]
      (when (listing/fts-query q) q))))

(defn- board-filters
  "The board's filters in `params`. Values that are not known kinds or
  offered categories are ignored."
  [params settings]
  (let [[q kind category] (map #(params/one params %) ["q" "kind" "category"])]
    {:q (search-terms q)
     ;; Kept to show back when there is nothing to search for in it.
     :typed (some-> q str/trim not-empty)
     :kind (when (listing/kinds kind) kind)
     :category (when (some #(= category (:id %)) (choices/choosable (:categories settings))) category)}))

(defn index
  "The board, or search results when there is a query."
  [{:keys [ctx params user] :as request}]
  (let [{:keys [db blobs settings]} ctx
        [before page] (map #(params/one params %) ["before" "page"])
        ;; Where on the board this page is, for forms on it to come back to.
        filters (assoc (board-filters params settings) :at {:before before :page page})
        result (-> (if (:q filters)
                     (listing/search db (assoc filters
                                               :page (some->> page (re-matches #"\d{1,3}") parse-long)
                                               :limit page-size))
                     (listing/board db (assoc filters :before before :limit page-size)))
                   (update :listings #(mark-favorites db user %)))
        photo-url (listing/photo-urls blobs (Instant/now))
        facets #(listing/facets db filters)]
    (cond
      (not (response/datastar? request))
      (response/html 200 (view/board request filters result (facets) photo-url))

      (= "more" (get-in request [:headers "jumble-fragment"]))
      (-> (response/html 200 (view/more-fragment request filters result photo-url))
          (update :headers assoc "datastar-selector" "#more" "datastar-mode" "replace"))

      :else
      (response/html 200 (view/results-fragment request filters result (facets) photo-url)))))

(defn mine
  [{:keys [ctx user] :as request}]
  (response/html 200 (view/mine request
                                (listing/by-owner (:db ctx) (:id user))
                                (listing/photo-urls (:blobs ctx) (Instant/now)))))

(defn saved-searches
  [{:keys [ctx user query-string] :as request}]
  (response/html 200 (view/saved-searches request (saved/by-user (:db ctx) (:id user)) (= "saved" query-string))))

(defn save-search
  "Saves the board's current search and filters."
  [{:keys [ctx params user] :as request}]
  (let [{:keys [q kind category]} (board-filters params (:settings ctx))]
    (case (:error (saved/save! (:db ctx) (:id user) {:q q :kind kind :category category} (System/currentTimeMillis)))
      :empty (layout/message request 422 (i18n/t "Nothing to save")
                             (i18n/t "Search for something, or choose a type or a category, then save that.")
                             [:a.button {:href "/listings"} (i18n/t "Back to the listings")])
      :too-many (layout/message request 422 (i18n/t "Too many saved searches")
                                (i18n/t "You can keep up to {max} saved searches. Remove one first."
                                        {:max saved/max-per-member})
                                [:a.button {:href "/my/searches"} (i18n/t "Your saved searches")])
      nil (response/redirect "/my/searches?saved"))))

(defn remove-search
  [{:keys [ctx path-params user] :as request}]
  (if (some->> (params/id (:id path-params)) (saved/remove! (:db ctx) (:id user)))
    (response/redirect "/my/searches")
    (layout/not-found request)))

(defn favorites
  "The user's favorites, those that changed since last time marked; seeing
  them is what clears the header's count."
  [{:keys [ctx user] :as request}]
  (let [found (mark-favorites (:db ctx) user (listing/favorites (:db ctx) (:id user)))]
    (listing/mark-favorites-seen! (:db ctx) (:id user) found)
    (response/html 200 (view/favorites request found (listing/photo-urls (:blobs ctx) (Instant/now))))))

(defn member
  "A member's page: their listings on the board, what anyone can see of
  them there."
  [{:keys [ctx path-params user] :as request}]
  (let [{:keys [db blobs]} ctx
        found (some->> (params/id (:id path-params)) (account/member db))]
    (if found
      (response/html 200 (view/member request found
                                      (mark-favorites db user (filterv #(listing/on-board (:status %))
                                                                       (listing/by-owner db (:id found))))
                                      (listing/photo-urls blobs (Instant/now))))
      (layout/not-found request (i18n/t "This member does not exist, or no longer does.")))))

(defn show
  [{:keys [ctx path-params user] :as request}]
  (let [{:keys [db blobs]} ctx
        found (some->> (params/id (:id path-params)) (listing/by-id db) vector (mark-favorites db user) first)]
    (if found
      (response/html 200 (view/show request found (listing/photo-urls blobs (Instant/now))))
      (layout/not-found request (i18n/t "This listing does not exist, or no longer does.")))))

(defn- own-listing
  "The listing in the path when it belongs to the requesting user."
  [{:keys [ctx path-params user]}]
  (when-let [found (some->> (params/id (:id path-params)) (listing/by-id (:db ctx)))]
    (when (= (:user-id found) (:id user))
      found)))

(defn change-status
  "Owner status change. Moves that are not allowed leave the listing as it
  was; the response shows its current state either way."
  [{:keys [ctx params user] :as request}]
  (if-let [{:keys [id]} (own-listing request)]
    (do (let [status (params/one params "status")]
          (when (and (listing/set-status! (:db ctx) id (:id user) status (System/currentTimeMillis))
                     (#{"sold" "given" "found"} status))
            (metrics/inc! listing/closed {:outcome status})))
        (cond
          (not (response/datastar? request)) (response/redirect (str "/listings/" id))
          ;; Removed in the meantime, from another tab.
          (nil? (listing/by-id (:db ctx) id)) (layout/not-found request (i18n/t "This listing does not exist, or no longer does."))
          :else (response/html 200 (view/status-fragment request (listing/by-id (:db ctx) id)))))
    (layout/not-found request (i18n/t "This listing does not exist, or no longer does."))))

(defn confirm-remove
  [request]
  (if-let [found (own-listing request)]
    (response/html 200 (view/remove-confirmation request found))
    (layout/not-found request (i18n/t "This listing does not exist, or no longer does."))))

(defn remove-listing
  [{:keys [ctx user] :as request}]
  (if-let [{:keys [id]} (own-listing request)]
    (do (listing/delete! (:db ctx) (:blobs ctx) id (:id user))
        (metrics/inc! listing/closed {:outcome "deleted"})
        (response/redirect "/my/listings"))
    (layout/not-found request (i18n/t "This listing does not exist, or no longer does."))))

(defn favorite
  "Adds the listing in the path to the user's favorites, or takes it out.
  Datastar gets the heart back, pressed or not; a plain form goes back to
  the page it was on."
  [{:keys [ctx params path-params user] :as request}]
  (let [db (:db ctx)
        found (some->> (params/id (:id path-params)) (listing/by-id db))
        return-to (auth/local-path (params/one params "return_to"))]
    (if (or (nil? found) (= (:id user) (:user-id found)))
      (layout/not-found request (i18n/t "This listing does not exist, or no longer does."))
      (let [on (if (= "on" (params/one params "favorite"))
                 (listing/favorite! db (:id user) (:id found) (System/currentTimeMillis))
                 (do (listing/unfavorite! db (:id user) (:id found)) false))]
        (if (response/datastar? request)
          (response/html 200 (view/favorite-fragment (if (= "card" (params/one params "view")) :card :listing)
                                                     found on return-to))
          (response/redirect return-to))))))

(defn confirm-fresh
  "The owner says the listing in the path is still available."
  [{:keys [ctx user] :as request}]
  (if-let [{:keys [id]} (own-listing request)]
    (if (listing/confirm-fresh! (:db ctx) id (:id user) (System/currentTimeMillis))
      (response/redirect (str "/listings/" id))
      (layout/message request 409 (i18n/t "Not on the board")
                      (i18n/t "This listing is no longer on the board: put it back on the board instead.")
                      [:a.button {:href (str "/listings/" id)} (i18n/t "Back to the listing")]))
    (layout/not-found request (i18n/t "This listing does not exist, or no longer does."))))

(defn- form-values-of
  "A stored listing as raw form values."
  [{:keys [currency]} {:keys [kind title description price-minor category pickup-city] :as stored}]
  {:kind kind :title title :description description
   :price (some->> price-minor (money/input-value currency))
   :category category
   :pickup (listing/pickup-value stored)
   :city pickup-city})

(defn- editing
  "Calls `(f listing)` with the own listing in the path when it can be
  edited (on the board); otherwise answers why not."
  [request f]
  (if-let [found (own-listing request)]
    (if (listing/on-board (:status found))
      (f found)
      (layout/message request 409 (i18n/t "Not on the board")
                      (i18n/t "Only listings on the board can be edited. Put it back on the board first.")
                      [:a.button {:href (str "/listings/" (:id found))} (i18n/t "Back to the listing")]))
    (layout/not-found request (i18n/t "This listing does not exist, or no longer does."))))

(defn edit-form
  [{:keys [ctx] :as request}]
  (editing request
           (fn [found]
             (response/html 200 (view/form request {:values (form-values-of (:settings ctx) found)
                                                    :listing found
                                                    :photo-url (listing/photo-urls (:blobs ctx) (Instant/now))})))))

(defn- photo-ids
  "Photo ids ticked for removal."
  [params]
  (let [v (get params "remove")]
    (into #{} (keep params/id) (if (string? v) [v] v))))

(defn- photo-order
  "The photo ids in the order the form sends them, the cover chosen first:
  without scripts only the cover is chosen, the rest keep their order."
  [params]
  (let [v (get params "order")
        order (keep params/id (if (string? v) [v] v))]
    (distinct (cond->> order
                (params/id (params/one params "cover")) (cons (params/id (params/one params "cover")))))))

(defn update-listing
  "Like [[create]]: fields are validated before photos are processed."
  [{:keys [ctx params user] :as request}]
  (editing
   request
   (fn [found]
     (let [{:keys [db blobs settings]} ctx
           values (form-values params)
           files (uploads params)
           removing (photo-ids params)
           form {:values values :listing found :removing removing :photo-url (listing/photo-urls blobs (Instant/now))}
           {:keys [listing errors]} (listing/validate settings values)]
       (if errors
         (invalid-form request (assoc form :photos-dropped (seq files)) errors)
         (let [{:keys [photos errors]} (listing/prepare-photos files)
               result (when-not errors
                        (listing/update! db blobs (:id found) (:id user) listing removing photos
                                         (System/currentTimeMillis) {:order (photo-order params)}))]
           (cond
             errors (invalid-form request (assoc form :photos-dropped true) errors)
             (:ok result) (response/redirect (str "/listings/" (:id found)))
             (:errors result) (invalid-form request (assoc form :photos-dropped true) (:errors result))
             :else (layout/not-found request (i18n/t "This listing does not exist, or no longer does.")))))))))

(defn- back-to-form
  "A link back to the form an upload came from: the new listing's, or the
  edit form of `/listings/<id>`."
  [uri]
  [:a.button {:href (if (= "/listings" uri) "/listings/new" (str uri "/edit"))}
   (i18n/t "Back to the form")])

(defn- too-large
  "Shown when the upload broke the parser's limits."
  [{:keys [uri] :as request}]
  (layout/message request
                  413
                  (i18n/t "Upload too large")
                  (i18n/t "A listing can have up to {max} photos of at most {megabytes} MB each."
                          {:max listing/max-photos :megabytes (quot max-photo-bytes (* 1024 1024))})
                  (back-to-form uri)))

(defn- uploads-busy
  "Shown to an upload without a turn (see [[listing/with-upload-turn]])."
  [{:keys [uri] :as request}]
  (layout/message request
                  503
                  (i18n/t "Try again")
                  (i18n/t "Too many photos are being processed right now. Please try again in a moment.")
                  (back-to-form uri)))

(defn- wrap-upload-turn
  "Takes an upload in only when it has a turn, before its body is read."
  [handler]
  (fn [{:keys [user] :as request}]
    (listing/with-upload-turn (:id user) #(handler request) #(uploads-busy request))))

(def ^:private wrap-uploads
  #(wrap-multipart-params % {:store (byte-array-store)
                             :max-file-size max-photo-bytes
                             ;; Ring counts every part, fields included: the
                             ;; form's fields, when editing a place, a remove
                             ;; box and the cover choice for each photo, and
                             ;; one file more than allowed so too many photos
                             ;; get the form's own message.
                             :max-file-count (+ (count (form-values {})) (* 3 listing/max-photos) listing/max-photos 1)
                             :error-handler too-large}))

(def routes
  {[:get "/listings"] (for-members #'index)
   [:get "/my/listings"] (for-members #'mine)
   [:get "/members/{id}"] (for-members #'member)
   [:get "/my/favorites"] (for-members #'favorites)
   [:get "/my/searches"] (for-members #'saved-searches)
   [:post "/my/searches"] (for-members #'save-search)
   [:post "/my/searches/{id}/remove"] (auth/require-user #'remove-search)
   [:get "/listings/new"] (for-members #'new-form)
   [:post "/listings"] (for-members (wrap-upload-turn (wrap-uploads #'create)))
   [:get "/listings/{id}"] (for-members #'show)
   [:get "/listings/{id}/edit"] (for-members #'edit-form)
   [:post "/listings/{id}"] (for-members (wrap-upload-turn (wrap-uploads #'update-listing)))
   [:post "/listings/{id}/status"] (for-members #'change-status)
   [:post "/listings/{id}/fresh"] (auth/require-user #'confirm-fresh)
   [:post "/listings/{id}/favorite"] (auth/require-user #'favorite)
   [:get "/listings/{id}/remove"] (for-members #'confirm-remove)
   [:post "/listings/{id}/remove"] (for-members #'remove-listing)})
