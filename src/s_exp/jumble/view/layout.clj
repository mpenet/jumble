(ns s-exp.jumble.view.layout
  "The page shell every page shares, header and footer, message pages, and
  helpers for forms."
  (:require [dev.onionpancakes.chassis.core :as h]
            [s-exp.jumble.page.assets :as assets]
            [s-exp.jumble.page.branding :as branding]
            [s-exp.jumble.view.avatar :as avatar]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.view.icons :as icons]
            [s-exp.jumble.web.response :as response]
            [s-exp.jumble.page.theme :as theme]))

(set! *warn-on-reflection* true)

(def once
  "Attributes of a form sent once: a second click on its submit buttons
  while the first submit is on its way (a double click, an impatient Enter,
  which clicks the default button) is ignored. Only a form the browser
  would send counts as sent. Coming back to the page, from the browser's
  cache, makes it sendable again. On click rather than submit: Datastar
  cancels the browser's submission of forms with a submit handler."
  {:data-on:click (str "evt.target.closest('[type=submit]') && "
                       "(el.dataset.sent ? evt.preventDefault() : el.checkValidity() && (el.dataset.sent = 'true'))")
   :data-on:pageshow__window "delete el.dataset.sent"})

(defn field-error
  "The message for `field` in `errors`, if any, for [[invalid]] to point at."
  [errors field]
  (when-let [message (get errors field)]
    [:p.field-error {:id (str (name field) "-error")} message]))

(defn invalid
  "Attributes marking an input invalid and pointing at its message."
  [errors field]
  (when (get errors field)
    {:aria-invalid "true" :aria-describedby (str (name field) "-error")}))

(defn- nav-link
  "A header link, its icon before its label, marked current on its page;
  `badge`, a count, is read out as that count followed by `(what count)`
  (\"unread\", say: the word agrees with the count in some languages)."
  [uri href icon label & [badge what]]
  [:a (cond-> {:href href} (= uri href) (assoc :aria-current "page"))
   icon
   [:span label]
   (when (and badge (pos? badge)) [:span.badge badge [:span.sr-only (str " " (what badge))]])])

(defn- themed
  "An image, or one per theme when there is a dark version, the other hidden
  by CSS. Both carry the name: only one ever shows."
  [class light dark attrs]
  (if dark
    (list [:img (merge {:class (str class " only-light") :src light} attrs)]
          [:img (merge {:class (str class " only-dark") :src dark} attrs)])
    [:img (merge {:class class :src light} attrs)]))

(defn- own-brand?
  "Whether the instance shows a name or logo of its own rather than Jumble's."
  [{:keys [app-name logo]}]
  (or (some? logo) (not= "Jumble" app-name)))

(defn community-mark
  "The community's logo, else Jumble's wordmark, else the Jumble mark beside
  the community's name."
  [{:keys [app-name logo logo-dark]}]
  (cond
    logo (themed "community-logo" (branding/url logo) (some-> logo-dark branding/url) {:alt app-name})
    (= "Jumble" app-name) (themed "wordmark" (assets/url "logo.png") (assets/url "logo-white.png")
                                  {:alt "Jumble" :width 109 :height 32})
    :else (list [:img.mark {:src (assets/url "mark.png") :alt "" :width 37 :height 28}]
                [:span app-name])))

(defn- brand
  [settings]
  [:a.brand {:href "/"} (community-mark settings)])

(defn- account-menu
  "Under the member's name: their account, and for moderators managing the
  community, flagged on the name while there are reports to review. A plain
  <details>, closed by a click elsewhere or Escape; with a mouse, opened by
  pointing at it, and closed a moment after leaving it, time enough to
  reach the panel."
  [request user uri]
  (let [reports (when (:moderator request) (some-> (:open-reports request) deref))
        to-review #(i18n/tn "to review" "to review" %)]
    [:details.account-menu {:data-on:click__outside "el.open = false"
                            :data-on:keydown__window
                            "evt.key === 'Escape' && el.open && (el.open = false, el.querySelector('summary').focus())"
                            :data-on:mouseenter
                            "matchMedia('(hover: hover)').matches && (clearTimeout(el.closing), el.open = true)"
                            :data-on:mouseleave
                            "matchMedia('(hover: hover)').matches && (el.closing = setTimeout(() => el.open = false, 300))"}
     ;; Opened by pointing, a click must not close it at once.
     [:summary.user-name {:data-on:click "matchMedia('(hover: hover)').matches && el.parentElement.open && evt.preventDefault()"}
      (avatar/avatar user)
      [:span.user-name-text (:name user)]
      (when (and reports (pos? reports))
        [:span.menu-flag [:span.sr-only (str reports " " (to-review reports))]])
      icons/chevron-down]
     [:div.menu-panel
      (nav-link uri "/settings" icons/user (i18n/t "Settings"))
      (nav-link uri "/my/searches" icons/search (i18n/t "Saved searches"))
      (when (:moderator request)
        (nav-link uri "/manage" icons/shield (i18n/t "Manage") reports to-review))
      [:form {:method "post" :action "/logout"}
       [:button.link-button {:type "submit"} (i18n/t "Sign out")]]]]))

(defn- header
  [request user uri]
  [:header.site-header
   [:div.container.header-inner
    (brand (get-in request [:ctx :settings]))
    (when user
      (list
       [:nav.site-nav {:aria-label (i18n/t "Main")}
        (nav-link uri "/listings" icons/grid (i18n/t "Listings"))
        (nav-link uri "/my/listings" icons/tag (i18n/t "My listings"))
        (nav-link uri "/my/favorites" icons/heart (i18n/t "Favorites") (some-> (:changed-favorites request) deref)
                  #(i18n/tn "changed" "changed" %))
        (nav-link uri "/messages" icons/chat (i18n/t "Messages") (some-> (:unread-messages request) deref)
                  #(i18n/tn "unread" "unread" %))]
       [:div.account
        [:a.button.button-pop.post-button {:href "/listings/new"} icons/plus [:span (i18n/t "Post a listing")]]
        (theme/toggle request)
        (account-menu request user uri)]))]])

(defn page
  "HTML document for `request`, rendering `content` in the page shell.
  Carries the request's CSP nonce on <html data-nonce>, where Datastar reads
  it to run its expressions under the policy."
  [{:keys [csp-nonce user ctx uri] :as request} {:keys [title]} & content]
  (let [app-name (get-in ctx [:settings :app-name])]
    (h/html
     [h/doctype-html5
      [:html {:lang (i18n/language) :data-theme (theme/chosen request)
              :data-palette (some-> (:palette request) deref) :data-nonce csp-nonce}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:title (if title (str title " · " app-name) app-name)]
        [:link {:rel "icon" :href (assets/url "favicon.png") :type "image/png"}]
        [:link {:rel "apple-touch-icon" :href (assets/url "apple-touch-icon.png")}]
        [:link {:rel "manifest" :href "/manifest.webmanifest"}]
        [:link {:rel "stylesheet" :href (assets/url "app.css")}]
        [:script {:type "module" :src (assets/url "datastar.js")}]]
       ;; A Datastar request answered with an error (expired session, missing
       ;; listing, server fault) would otherwise do nothing visible. Reloading
       ;; shows the page the current URL leads to: login, not found, error.
       [:body {:data-on:datastar-fetch "evt.detail.type === 'error' && location.reload()"}
        (header request user uri)
        [:main.container content]
        [:footer.site-footer
         [:div.container
          [:p (i18n/t "{app-name} — sell, give away, lend and find things within your community." {:app-name app-name})]
          [:p.footer-links
           [:a {:href "/privacy"} (i18n/t "Privacy")]
           (when-let [url (get-in ctx [:settings :source-url])]
             [:a {:href url} (i18n/t "Source code")])
           [:a {:href "https://jumbl.ing"} (i18n/t "Get your own Jumble")]
           [:span (i18n/t "Built by {maker}" {:maker [:a {:href "https://s-exp.com"} "s-exp.com"]})]
           (when-let [{:keys [name url]} (get-in ctx [:settings :hosted-by])]
             [:span (i18n/t "Hosted by {host}" {:host (if url [:a {:href url} name] name)})])]
          (when (own-brand? (:settings ctx))
            [:p.powered-by [:img {:src (assets/url "mark.png") :alt "" :width 21 :height 16}] (i18n/t "Powered by Jumble")])]]]]])))

(defn message
  "Response with a page holding just a heading, an explanation, and `actions`."
  [request status title text & actions]
  (response/html status (page request {:title title}
                              [:section.message
                               icons/empty-box
                               [:h1 title]
                               [:p text]
                               (when (seq actions) [:div.message-actions actions])])))

(defn not-found
  "The 404 page, saying `explanation` (nothing at this address, by default)."
  ([request]
   (not-found request (i18n/t "There is nothing at this address.")))
  ([request explanation]
   (message request 404 (i18n/t "Not found") explanation
            [:a.button {:href "/"} (i18n/t "Go to the home page")])))
