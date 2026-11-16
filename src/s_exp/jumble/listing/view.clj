(ns s-exp.jumble.listing.view
  "Pages and fragments of listings: the board, a listing, its form, and a
  member's own, favorite ones and saved searches."
  (:require [clojure.string :as str]
            [dev.onionpancakes.chassis.core :as h]
            [ring.util.codec :as codec]
            [s-exp.oda :as oda]
            [s-exp.jumble.view.dates :as dates]
            [s-exp.jumble.view.avatar :as avatar]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.view.icons :as icons]
            [s-exp.jumble.view.layout :as layout]
            [s-exp.jumble.listing.choices :as choices]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.listing.saved-searches :as saved]
            [s-exp.jumble.view.markdown :as markdown]
            [s-exp.jumble.money :as money]))

(set! *warn-on-reflection* true)

(defn label-of
  "Label of `id` in `entries`, retired ones included, or the id itself."
  [entries id]
  (or (some #(when (= id (:id %)) (:label %)) entries) id))

(defn- radio
  [field value checked label attrs]
  [:label.choice
   [:input (merge {:type "radio" :name field :value value :checked checked} attrs)]
   label])

(defn- signals
  "Initial Datastar signals. Only values from fixed sets ever reach them:
  Datastar evaluates this attribute as an expression. A pickup not offered
  (a place retired since) is left for the user to choose."
  [{:keys [kind pickup]} places]
  (let [pickups (into #{"city"} (map (comp listing/place-pickup :id)) places)]
    (oda/write-str {:kind (if (listing/kinds kind) kind (:kind (listing/default-values {})))
                    :pickup (if (pickups pickup) pickup "")})))

(defn- thumbnail
  "<img> of a photo's thumbnail, sized like the stored file (longest edge
  480) so the page does not shift while it loads."
  [photo-url {:keys [object-key width height]} alt]
  (let [scale (min 1.0 (/ 480.0 (max width height)))]
    [:img {:src (photo-url object-key "thumb") :alt alt :loading "lazy"
           :width (Math/round (* scale (double width)))
           :height (Math/round (* scale (double height)))}]))

(defn- shown-when
  "Attributes showing an element while Datastar `expression` holds, and
  hidden from the start unless `shown`, so that it does not flash before
  the scripts run."
  [expression shown]
  (cond-> {:data-show expression} (not shown) (assoc :style "display: none")))

(defn form
  "The listing form, new or for editing `listing`, filled with `values` (raw
  form input) and showing `errors`. When editing, the listing's photos are
  shown with a remove box each (ticked for ids in `removing`).
  `photos-dropped` tells the user files must be chosen again."
  [{:keys [ctx] :as request} {:keys [values errors photos-dropped listing removing photo-url]}]
  (let [{:keys [categories places currency]} (:settings ctx)
        categories (choices/choosable categories)
        places (choices/choosable places)
        {:keys [kind title description price category pickup city]} values
        initial-signals (signals values places)
        heading (if listing (i18n/t "Edit listing") (i18n/t "New listing"))]
    (layout/page
     request {:title heading}
     [:h1 heading]
     [:form.listing-form (merge {:method "post" :action (if listing (str "/listings/" (:id listing)) "/listings")
                                 :enctype "multipart/form-data"
                                 :data-signals initial-signals :novalidate true}
                                layout/once)
      [:fieldset.segmented
       [:legend (i18n/t "You want to")]
       (for [[value label] [["sale" (i18n/t "Sell it")] ["free" (i18n/t "Give it away")] ["lend" (i18n/t "Lend it")]
                            ["wanted" (i18n/t "Look for it")]]]
         (radio "kind" value (= value (if (listing/kinds kind) kind (:kind (listing/default-values {})))) label
                (merge {:data-bind "kind"} (layout/invalid errors :kind))))
       (layout/field-error errors :kind)]

      [:label {:for "title"} (i18n/t "Title")]
      [:input (merge {:id "title" :name "title" :value title :maxlength 120 :required true}
                     (layout/invalid errors :title))]
      (layout/field-error errors :title)

      [:div (shown-when "$kind === 'sale'" (= "sale" kind))
       [:label {:for "price"} (i18n/t "Price ({currency})" {:currency currency})]
       [:input (merge {:id "price" :name "price" :value price :inputmode "decimal" :autocomplete "off"}
                      (layout/invalid errors :price))]
       (layout/field-error errors :price)]

      [:label {:for "category"} (i18n/t "Category")]
      [:select (merge {:id "category" :name "category"} (layout/invalid errors :category))
       (when (and category (not-any? #(= category (:id %)) categories))
         [:option {:value "" :selected true :disabled true} (i18n/t "Choose a category")])
       (for [{:keys [id label]} categories]
         [:option {:value id :selected (= id category)} label])]
      (layout/field-error errors :category)

      [:fieldset
       ;; Where to pick it up, or for a wanted post where it is wanted.
       (let [wanted (= "wanted" kind)]
         [:legend
          [:span (shown-when "$kind !== 'wanted'" (not wanted)) (i18n/t "Pickup")]
          [:span (shown-when "$kind === 'wanted'" wanted) (i18n/t "Where")]])
       (for [{:keys [id label]} places
             :let [value (listing/place-pickup id)]]
         (radio "pickup" value (= value pickup) label (merge {:data-bind "pickup"} (layout/invalid errors :pickup))))
       (radio "pickup" "city" (= "city" pickup) (i18n/t "Elsewhere")
              (merge {:data-bind "pickup"} (layout/invalid errors :pickup)))
       (layout/field-error errors :pickup)
       [:div (shown-when "$pickup === 'city'" (= "city" pickup))
        [:label {:for "city"} (i18n/t "City")]
        [:input (merge {:id "city" :name "city" :value city :maxlength 80} (layout/invalid errors :city))]
        (layout/field-error errors :city)]]

      [:label {:for "description"} (i18n/t "Description")]
      [:textarea (merge {:id "description" :name "description" :rows 6 :maxlength 5000}
                        (layout/invalid errors :description))
       description]
      [:p.field-note (i18n/t "Markdown works: **bold**, _italics_, lists, links.")]
      (layout/field-error errors :description)

      (when (seq (:photos listing))
        [:fieldset.current-photos
         [:legend (i18n/t "Photos")]
         [:p.field-note (i18n/t "The cover photo shows first, on the board and on the listing.")]
         ;; The order is that of the list as sent; with scripts, moving a
         ;; photo, or choosing it as cover, moves it in the list.
         [:ol.photo-order
          (for [[i {:keys [id] :as photo}] (map-indexed vector (:photos listing))
                :let [number (inc i)
                      to-cover "el.closest('ol').querySelector('input[name=cover]').checked = true"]]
            [:li
             (thumbnail photo-url photo "")
             [:input {:type "hidden" :name "order" :value id}]
             [:span.photo-choices
              [:label.choice
               [:input {:type "radio" :name "cover" :value id :checked (zero? i)
                        :data-on:change "const li = el.closest('li'); li.parentElement.prepend(li)"}]
               (i18n/t "Cover")]
              [:label.choice
               [:input {:type "checkbox" :name "remove" :value id :checked (contains? removing id)}]
               (i18n/t "Remove photo {number}" {:number number})]]
             [:span.photo-moves {:style "display: none" :data-show "true"}
              [:button.icon-button {:type "button" :aria-label (i18n/t "Move photo {number} earlier" {:number number})
                                    :data-on:click (str "const li = el.closest('li'); li.previousElementSibling && li.previousElementSibling.before(li); " to-cover)}
               "↑"]
              [:button.icon-button {:type "button" :aria-label (i18n/t "Move photo {number} later" {:number number})
                                    :data-on:click (str "const li = el.closest('li'); li.nextElementSibling && li.nextElementSibling.after(li); " to-cover)}
               "↓"]]])]])

      [:label {:for "photos"} (if listing
                                (i18n/t "Add photos (up to {max} in all, JPEG, PNG, HEIC or AVIF)" {:max listing/max-photos})
                                (i18n/t "Photos (up to {max} in all, JPEG, PNG, HEIC or AVIF)" {:max listing/max-photos}))]
      [:input (merge {:id "photos" :name "photos" :type "file" :multiple true
                      :accept "image/jpeg,image/png,image/heic,image/heif,image/avif"}
                     (layout/invalid errors :photos))]
      (when photos-dropped
        [:p.field-note (i18n/t "Please select the photos again.")])
      (layout/field-error errors :photos)

      [:button {:type "submit"} (if listing (i18n/t "Save changes") (i18n/t "Publish listing"))]])))

(defn price-label
  "What a listing is: its price when for sale, else its kind."
  [currency {:keys [kind price-minor]}]
  (case kind
    "sale" (money/format currency price-minor)
    "free" (i18n/t "Free")
    "lend" (i18n/t "To lend")
    "wanted" (i18n/t "Wanted")))

(defn- status-label
  [status]
  (case status
    "reserved" (i18n/t "Reserved")
    "sold" (i18n/t "Sold")
    "given" (i18n/t "Given away")
    "found" (i18n/t "Found")
    "expired" (i18n/t "Expired")
    nil))

(defn- status-badge
  "The status of a listing not simply available; reserved ones are still
  on the board, and look it."
  [status]
  (when-let [label (status-label status)]
    [:span {:class (str "status" (when (= "reserved" status) " status-reserved"))} label]))

(defn- move-label
  "The button moving a listing from status `from` to `to`."
  [from to]
  (case to
    "reserved" (i18n/t "Mark as reserved")
    "sold" (i18n/t "Mark as sold")
    "given" (i18n/t "Mark as given away")
    "found" (i18n/t "Mark as found")
    "active" (if (= "reserved" from) (i18n/t "Make available again") (i18n/t "Put back on the board"))))

(defn pickup-label
  "Where a listing is picked up: its place's label, or its city."
  [places {:keys [pickup-place pickup-city]}]
  (if pickup-place (label-of places pickup-place) pickup-city))

(defn- favorite-form
  "The heart adding `listing` to the user's favorites, pressed when `on`
  is: on the listing's page (`:listing`) or on a card (`:card`). Without
  JavaScript, the form comes back to `return-to`."
  [view {:keys [id title]} on return-to]
  (let [card (= :card view)]
    [:form {:id (str (if card "card-favorite-" "favorite-") id)
            :class (if card "card-favorite" "favorite")
            :method "post" :action (str "/listings/" id "/favorite")
            :data-on:submit "@post(el.action, {contentType: 'form'})"}
     [:input {:type "hidden" :name "favorite" :value (if on "off" "on")}]
     [:input {:type "hidden" :name "view" :value (name view)}]
     [:input {:type "hidden" :name "return_to" :value return-to}]
     (if card
       [:button.favorite-button {:type "submit" :aria-pressed (str (boolean on)) :aria-label (i18n/t "Favorite {title}" {:title title})}
        icons/heart]
       [:button.secondary.favorite-button {:type "submit" :aria-pressed (str (boolean on))}
        icons/heart [:span (i18n/t "Favorite")]])]))

(defn favorite-fragment
  [view listing on return-to]
  (h/html (favorite-form view listing on return-to)))

(defn- status-section
  "Price, category, pickup and status, the contact button and the heart
  for others (whether it is one of their favorites is `:favorite`), and the owner's
  actions. Replaced on its own when the owner changes the status with
  Datastar."
  [{:keys [ctx user]} {:keys [id kind category status user-id] :as listing}]
  (let [{:keys [categories places currency]} (:settings ctx)
        owner (= user-id (:id user))]
    [:section#listing-status
     [:div.listing-price-row {:aria-live "polite"}
      [:p {:class (str "listing-price price-" kind)} (price-label currency listing)]
      (status-badge status)]
     [:dl.listing-facts
      [:div [:dt icons/tag (i18n/t "Category")] [:dd (label-of categories category)]]
      [:div [:dt icons/pin (if (= "wanted" kind) (i18n/t "Where") (i18n/t "Pickup"))] [:dd (pickup-label places listing)]]]
     (when-not owner
       [:div.listing-contact
        (when (listing/on-board status)
          [:a.button.button-large {:href (str "/listings/" id "/message")} icons/mail
           (if (= "wanted" (:kind listing))
             (i18n/t "I have one")
             (i18n/t "Message {name}" {:name (:owner-name listing)}))])
        (favorite-form :listing listing (:favorite listing) (str "/listings/" id))])
     (when owner
       [:div.owner-actions
        [:p.owner-actions-title (i18n/t "Your listing")]
        (when (and (:reminded-at listing) (listing/on-board status))
          [:form.still-available (merge {:method "post" :action (str "/listings/" id "/fresh")} layout/once)
           [:p (if (= "wanted" (:kind listing))
                 (i18n/t "Still looking? Without an answer, it soon leaves the board.")
                 (i18n/t "Still available? Without an answer, it soon leaves the board."))]
           [:button {:type "submit"} (i18n/t "Yes, keep it on the board")]])
        (when-let [moves (seq (listing/status-moves listing))]
          [:form {:method "post" :action (str "/listings/" id "/status")
                  :data-on:submit "@post(el.action, {contentType: 'form'})"
                  :data-indicator:_saving true}
           (for [to moves]
             [:button.secondary {:type "submit" :name "status" :value to
                                 :data-attr:disabled "$_saving"}
              (move-label status to)])])
        [:div.owner-links
         (when (listing/on-board status)
           [:a {:href (str "/listings/" id "/edit")} (i18n/t "Edit")])
         [:a.danger-link {:href (str "/listings/" id "/remove")} (i18n/t "Remove listing")]]])]))

(defn status-fragment
  [request listing]
  (h/html (status-section request listing)))

(defn- gallery
  "One large photo with a strip of thumbnails to switch between them. The
  switch is a Datastar signal; without JS the first photo shows and the
  thumbnails link to the full-size images."
  [photo-url title photos]
  (if (empty? photos)
    [:div.gallery-empty icons/photo]
    [:div.gallery {:data-signals:photo "0"}
     (for [[i {:keys [object-key width height]}] (map-indexed vector photos)]
       [:a.gallery-main (cond-> {:href (photo-url object-key "full")
                                 :data-attr:hidden (str "$photo !== " i)}
                          (pos? i) (assoc :hidden true))
        [:img {:src (photo-url object-key "full") :alt title :width width :height height
               :loading (if (zero? i) "eager" "lazy")}]])
     (when (> (count photos) 1)
       [:div.gallery-thumbs
        (for [[i {:keys [object-key] :as photo}] (map-indexed vector photos)]
          [:a.gallery-thumb {:href (photo-url object-key "full")
                             :aria-label (i18n/t "Photo {number}" {:number (inc i)})
                             :aria-current (if (zero? i) "true" "false")
                             :data-attr:aria-current (str "$photo === " i " ? 'true' : 'false'")
                             :data-on:click__prevent (str "$photo = " i)
                             :data-class:active (str "$photo === " i)}
           (thumbnail photo-url photo "")])])]))

(defn show
  "The listing page. `photo-url` gives the URL of a photo's object key and size."
  [{:keys [user query-string] :as request} {:keys [id title description owner-name photos created-at user-id] :as listing}
   photo-url]
  (layout/page
   request {:title title}
   [:article.listing
    [:div.listing-gallery (gallery photo-url title photos)]
    [:div.listing-details
     [:h1 title]
     (status-section request listing)
     (when-let [rendered (markdown/render description)]
       [:div.description rendered])
     [:p.listing-owner (avatar/avatar {:id user-id :name owner-name}) [:span (i18n/t "Posted by {name}" {:name [:a {:href (str "/members/" user-id)} owner-name]})]
      [:span.listing-date icons/calendar (dates/date created-at)]]
     (when (and (:moderator request) (not= user-id (:id user)))
       [:p.moderation-link
        [:a.danger-link {:href (str "/manage/listings/" id "/remove")} (i18n/t "Remove listing (moderation)")]])
     (when (not= user-id (:id user))
       (if (= "reported" query-string)
         [:p.report-thanks {:role "status"} (i18n/t "Thanks, a moderator will have a look.")]
         [:details.report
          [:summary (i18n/t "Report this listing")]
          [:form (merge {:method "post" :action (str "/listings/" id "/report")} layout/once)
           [:label {:for "report-reason"} (i18n/t "What is wrong with it? (optional)")]
           [:textarea {:id "report-reason" :name "reason" :rows 3 :maxlength 1000}]
           [:button.secondary {:type "submit"} (i18n/t "Report")]]]))]]))

(defn remove-confirmation
  [request {:keys [id title]}]
  (layout/page
   request {:title (i18n/t "Remove listing")}
   [:section.message
    icons/empty-box
    [:h1 (i18n/t "Remove listing?")]
    [:p (i18n/t "“{title}” and its photos will be deleted. This cannot be undone." {:title title})]
    [:div.message-actions
     [:form (merge {:method "post" :action (str "/listings/" id "/remove")} layout/once)
      [:button.danger {:type "submit"} (i18n/t "Remove listing")]]
     [:a.button.secondary {:href (str "/listings/" id)} (i18n/t "Keep it")]]]))

(defn- board-href
  "Board URL for the given filters, leaving out the unset ones."
  [params]
  (let [query (for [k [:q :kind :category :before :page]
                    :let [v (get params k)]
                    :when v]
                (str (name k) "=" (codec/form-encode v)))]
    (if (seq query)
      (str "/listings?" (str/join "&" query))
      "/listings")))

(def ^:private replace-request
  "Aborts the board request in flight before starting another. Datastar only
  cancels repeats of the same URL, and each keystroke makes a new one: an
  earlier response arriving late would overwrite newer results."
  "window.jumbleBoard?.abort(); window.jumbleBoard = new AbortController(); ")

(def ^:private fetch-options
  "Board requests carry no client state, everything is in the URL."
  "requestCancellation: window.jumbleBoard, filterSignals: {exclude: /.*/}")

(def ^:private navigate
  "Filter links: fetch the results for the link, and make it the page URL."
  (str replace-request "@get(el.href, {" fetch-options "}); history.pushState(null, '', el.href)"))

(def ^:private search
  "Search form submit: fetch results for the form's non-empty fields and
  make that the page URL, without a history entry per keystroke."
  (str "const query = new URLSearchParams([...new FormData(el)].filter(([k, v]) => v)).toString(); "
       "const url = query ? '/listings?' + query : '/listings'; "
       replace-request "@get(url, {" fetch-options "}); history.replaceState(null, '', url)"))

(def ^:private restore
  "Back and forward: show the results and query of the URL now current."
  (str replace-request "@get(location.href, {" fetch-options "}); "
       "document.getElementById('q').value = new URLSearchParams(location.search).get('q') ?? ''"))

(def ^:private load-more
  (str replace-request "@get(el.href, {headers: {'Jumble-Fragment': 'more'}, " fetch-options "})"))

(defn- option
  "A filter link with its count. Options with nothing in them stay links
  (the other filter may change) but are marked unavailable."
  [{:keys [href label n current class dot clear]}]
  [:a (cond-> {:href href
               :class (str/join " " (cond-> [class] (zero? n) (conj "unavailable")))
               :data-on:click__prevent navigate
               :data-indicator:_loading true}
        current (assoc :aria-current "page"))
   (when dot [:span.dot])
   [:span.option-label label]
   [:span.count n]
   (when clear
     (list [:span.clear-option {:aria-hidden "true"} "×"]
           [:span.sr-only (i18n/t "clear filter")]))])

(defn- kind-filters
  []
  [[nil (i18n/t "All")] ["sale" (i18n/t "For sale")] ["free" (i18n/t "Free")] ["lend" (i18n/t "To lend")]
   ["wanted" (i18n/t "Wanted")]])

(defn cards-env
  "What cards need from the page: its `settings`, the `photo-url` function,
  and the path of the page, for their hearts to come back to."
  [{:keys [ctx]} photo-url return-to]
  {:settings (:settings ctx) :photo-url photo-url :return-to return-to})

(defn card
  "A listing on a grid, its title a heading of `level` (`:h2` unless under
  a section heading), with a heart unless `:favorite` is nil (one's own
  listings). `env` is from [[cards-env]]."
  ([env listing]
   (card env listing :h2))
  ([{:keys [settings photo-url return-to]} {:keys [id title kind category cover status favorite] :as listing} level]
   (let [{:keys [categories places currency]} settings
         link [:a.card {:href (str "/listings/" id)}
               [:div.card-media
                (if cover
                  ;; The card's heading names it: no need to say it twice.
                  (thumbnail photo-url cover "")
                  [:div.card-placeholder icons/photo])
                [:span {:class (str "price-tag price-" kind)} (price-label currency listing)]]
               [:div.card-body
                [(keyword (str (name level) ".card-title")) title]
                [:p.card-meta
                 [:span (label-of categories category)]
                 [:span.card-pickup icons/pin (pickup-label places listing)]
                 (status-badge status)
                 (when (:changed listing) [:span.changed (i18n/t "Changed")])]]]]
     (if (nil? favorite)
       link
       [:div.card-item link (favorite-form :card listing favorite return-to)]))))

(defn- search-filters
  "Hidden inputs carrying the active filters into searches. Replaced along
  with the results, so a search typed after a filter click keeps it."
  [{:keys [kind category]}]
  [:span#search-filters
   (when kind [:input {:type "hidden" :name "kind" :value kind}])
   (when category [:input {:type "hidden" :name "category" :value category}])])

(defn- search-form
  [{:keys [q typed] :as filters}]
  [:form#search.search {:method "get" :action "/listings" :role "search"
                        :data-on:submit search :data-indicator:_loading true}
   [:div.search-field
    icons/search
    [:input {:type "search" :name "q" :value (or q typed) :id "q" :maxlength 100
             :placeholder (i18n/t "Search listings") :aria-label (i18n/t "Search listings")
             :data-on:input__debounce.300ms "el.form.requestSubmit()"}]]
   ;; No button: results follow typing, and without JS Enter submits a
   ;; form with a single text field.
   (search-filters filters)])

(defn- more
  "Link to the next page, last in the card grid. With Datastar it is
  replaced by the next cards and the link after them."
  [{:keys [q] :as filters} next]
  (when next
    [:p#more.more
     [:a {:href (board-href (if q (assoc filters :page next) (assoc filters :before next)))
          :data-on:click__prevent load-more :data-indicator:_loading true}
      (if q (i18n/t "More results") (i18n/t "Older listings"))]]))

(def ^:private max-category-chips
  "Categories shown before the others go behind More categories."
  10)

(defn- shown-categories
  "The offered categories with listings in the current view, in their
  order, and the chosen one even without: a long list is mostly empty."
  [env facets category]
  (filterv (fn [{:keys [id]}] (or (= id category) (pos? (get-in facets [:categories id] 0))))
           (choices/choosable (get-in env [:settings :categories]))))

(defn- results
  [env {:keys [q kind category] :as filters} {:keys [listings next]} facets]
  [:div#results {:data-attr:aria-busy "$_loading" :data-on:popstate__window restore}
   ;; Kept across Datastar updates, so screen readers hear when results change.
   [:p.sr-only {:aria-live "polite"}
    (i18n/tn "Showing {n} listing" "Showing {n} listings" (count listings))]
   (when (and (:typed filters) (not q))
     [:p.search-note (i18n/t "Nothing to search for in “{query}”: search with letters or numbers." {:query (:typed filters)})])
   (when q
     (list [:h2.results-heading (i18n/t "Results for “{query}”" {:query q})]
           [:p.clear [:a {:href (board-href (dissoc filters :q)) :data-on:click__prevent navigate} (i18n/t "Clear search")]]))
   [:nav.filters {:aria-label (i18n/t "Filters")}
    [:div.filter-row {:role "group" :aria-labelledby "filter-type"}
     [:span.filter-label {:id "filter-type"} (i18n/t "Type")]
     [:div.kind-tabs
      (for [[value label] (kind-filters)]
        (option {:href (board-href {:q q :kind value :category category})
                 :label label
                 :n (if value (get-in facets [:kinds value] 0) (reduce + 0 (vals (:kinds facets))))
                 :current (= value kind)
                 :class (str "kind-tab kind-" (or value "all"))
                 :dot (some? value)}))]]
    [:div.filter-row {:role "group" :aria-labelledby "filter-category"}
     [:span.filter-label {:id "filter-category"} (i18n/t "Category")]
     [:div.filter-group
      (option {:href (board-href {:q q :kind kind})
               :label (i18n/t "All")
               :n (reduce + 0 (vals (:categories facets)))
               :current (nil? category)
               :class "chip"})
      (let [chip (fn [{:keys [id label]}]
                   (let [current (= id category)]
                     (option {:href (board-href (if current {:q q :kind kind} {:q q :kind kind :category id}))
                              :label label
                              :n (get-in facets [:categories id] 0)
                              :current current
                              :class "chip"
                              :clear current})))
            [first-chips more-chips] (split-at max-category-chips (shown-categories env facets category))]
        (list
         (map chip first-chips)
         (when (seq more-chips)
           [:details.more-categories (when (some #(= category (:id %)) more-chips) {:open true})
            [:summary (i18n/t "More categories ({count})" {:count (count more-chips)})]
            [:div.more-chips (map chip more-chips)]])))]]]
   (when (or q kind category)
     [:form.save-search (merge {:method "post" :action "/my/searches"} layout/once)
      (for [[k v] {"q" q "kind" kind "category" category} :when v]
        [:input {:type "hidden" :name k :value v}])
      [:button.secondary {:type "submit"} (i18n/t "Save this search")]
      [:span.field-note (i18n/t "Get an email when something new matches.")]])
   (if (seq listings)
     [:div#cards.cards
      (for [listing listings] (card env listing))
      (more filters next)]
     [:div.empty icons/empty-box
      [:p (if q (i18n/t "No listings match.") (i18n/t "Nothing here yet."))]
      [:a.button {:href "/listings/new"} (i18n/t "Post a listing")]])])

(defn board
  "Listings with a search box and filters by kind and category, paged.
  `filters` are the applied (valid) ones; with a `:q` the listings are
  search results, paged by number, otherwise the newest, paged by cursor.
  Works as plain links and forms; Datastar swaps in [[results-fragment]]
  and [[more-fragment]] instead of loading whole pages."
  [request {:keys [q] :as filters} result facets photo-url]
  (layout/page
   request {:title (when q (i18n/t "Search: {query}" {:query q}))}
   [:div.board-header
    [:h1 (i18n/t "Listings")]
    [:p.lede (i18n/t "What your community is selling, giving away, lending and looking for.") " "
     [:a {:href "/my/searches"} (i18n/t "Your saved searches")]]]
   (search-form filters)
   (results (cards-env request photo-url (board-href (merge filters (:at filters)))) filters result facets)))

(defn results-fragment
  "What a Datastar board request replaces, matched by id."
  [request filters result facets photo-url]
  (h/html (list (search-filters filters)
                (results (cards-env request photo-url (board-href (merge filters (:at filters)))) filters result facets))))

(defn more-fragment
  "The following cards and link, replacing the #more link."
  [request filters {:keys [listings next]} photo-url]
  (h/html (list (for [listing listings] (card (cards-env request photo-url (board-href (merge filters (:at filters)))) listing))
                (more filters next))))

(defn mine
  "The signed-in user's listings, any status."
  [request listings photo-url]
  (layout/page
   request {:title (i18n/t "My listings")}
   [:div.board-header
    [:h1 (i18n/t "My listings")]
    [:p.lede (i18n/t "Everything you posted, sold and given away included.")]]
   (if (seq listings)
     [:div.cards (for [listing listings] (card (cards-env request photo-url "/my/listings") listing))]
     [:div.empty icons/empty-box
      [:p (i18n/t "You have not posted anything yet.")]
      [:a.button {:href "/listings/new"} (i18n/t "Post a listing")]])))

(defn member
  "Member `member`'s page: who they are, and their `listings` on the board."
  [request {:keys [id name email created-at]} listings photo-url]
  (let [shown (if (str/blank? name) email name)]
    (layout/page
     request {:title shown}
     [:div.board-header.member-header
      (avatar/avatar {:id id :name shown})
      [:div
       [:h1 shown]
       [:p.lede (i18n/t "Member since {date}" {:date (dates/date created-at)})]]]
     (if (seq listings)
       [:div.cards (for [listing listings] (card (cards-env request photo-url (str "/members/" id)) listing))]
       [:div.empty icons/empty-box
        [:p (i18n/t "Nothing on the board right now.")]]))))

(defn favorites
  "The signed-in user's favorites, any status."
  [request listings photo-url]
  (layout/page
   request {:title (i18n/t "Favorites")}
   [:div.board-header
    [:h1 (i18n/t "Favorites")]
    [:p.lede (i18n/t "Listings you keep an eye on. Only you see them.")]]
   (if (seq listings)
     [:div.cards (for [listing listings] (card (cards-env request photo-url "/my/favorites") listing))]
     [:div.empty icons/heart
      [:p (i18n/t "No favorites yet.")]
      [:a.button {:href "/listings"} (i18n/t "Browse listings")]])))

(defn saved-searches
  "The user's saved searches, each with a way to it and to remove it."
  [{:keys [ctx] :as request} searches saved]
  (let [categories (get-in ctx [:settings :categories])]
    (layout/page
     request {:title (i18n/t "Saved searches")}
     [:div.board-header
      [:h1 (i18n/t "Saved searches")]
      [:p.lede (i18n/t "Once a day at most, you get an email with what is new for them. Save one from the listings, after searching or choosing a type or a category.")]]
     (when saved [:p.field-note {:role "status"} (i18n/t "Saved.")])
     (if (seq searches)
       [:section.members
        [:ul.member-list
         (for [{:keys [id] :as search} searches
               :let [described (saved/describe search categories)]]
           [:li
            [:a {:href (board-href search)} described]
            [:span.member-meta]
            [:form (merge {:method "post" :action (str "/my/searches/" id "/remove")} layout/once)
             [:button.link-button {:type "submit" :aria-label (i18n/t "Remove {search}" {:search described})} (i18n/t "Remove")]]])]]
       [:div.empty icons/search
        [:p (i18n/t "No saved searches yet.")]
        [:a.button {:href "/listings"} (i18n/t "Browse listings")]]))))
