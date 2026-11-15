(ns s-exp.jumble.moderation.view
  "Pages of moderation: reports, removing a listing, who may sign in and
  removing a member, categories and places, the community's palette."
  (:require [clojure.string :as str]
            [s-exp.jumble.view.dates :as dates]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.page.assets :as assets]
            [s-exp.jumble.view.icons :as icons]
            [s-exp.jumble.view.layout :as layout]
            [s-exp.jumble.listing.choices :as choices]
            [s-exp.jumble.page.palette :as palette]
            [s-exp.jumble.view.qr :as qr]))

(set! *warn-on-reflection* true)

(defn- sections
  []
  [["/manage" (i18n/t "Reports")]
   ["/manage/members" (i18n/t "Who may sign in")]
   ["/manage/categories" (i18n/t "Categories")]
   ["/manage/places" (i18n/t "Pickup places")]
   ["/manage/appearance" (i18n/t "Appearance")]
   ["/manage/poster" (i18n/t "Poster")]])

(defn- moderation-nav
  "Links between the moderators' pages, `current` marked, and the notice
  for moderators if there is one."
  [request current]
  (list
   [:nav.moderation-nav {:aria-label (i18n/t "Manage")}
    (for [[href label] (sections)]
      [:a (cond-> {:href href} (= href current) (assoc :aria-current "page")) label])]
   (when-let [text (layout/file-notice (get-in request [:ctx :settings :moderator-notice]))]
     [:aside.moderator-notice {:aria-label (i18n/t "Notice")} text])))

(defn reports
  "Reported listings for moderators, each with its reports and what can be done."
  [request listings]
  (layout/page
   request {:title (i18n/t "Reports")}
   [:div.board-header
    (moderation-nav request "/manage")
    [:h1 (i18n/t "Reports")]
    [:p.lede (i18n/t "Listings members reported, the longest waiting first.")]]
   (if (seq listings)
     [:ul.reports
      (for [{:keys [listing-id listing-title owner-id owner-name owner-email reports]} listings
            :let [reason-id (str "reason-" listing-id)]]
        [:li.report-card
         [:h2 [:a {:href (str "/listings/" listing-id)} listing-title]]
         [:p.lede (i18n/t "Posted by {owner} ({email})" {:owner owner-name :email owner-email})]
         [:ol.report-list
          (for [{:keys [reporter-name reason created-at]} reports]
            [:li
             [:div.post-meta [:strong (if (str/blank? reporter-name) (i18n/t "A former member") reporter-name)]
              [:span (dates/date-time created-at)]]
             (if (str/blank? reason)
               [:p.report-reason.none (i18n/t "No reason given")]
               [:p.report-reason reason])])]
         (if (= owner-id (get-in request [:user :id]))
           [:p.field-note (i18n/t "Your own listing: another moderator handles its reports.")]
           [:div.report-actions
            [:form (merge {:method "post" :action (str "/manage/listings/" listing-id "/remove")} layout/once)
             [:label {:for reason-id} (i18n/t "Reason, emailed to the owner (optional)")]
             [:textarea {:id reason-id :name "reason" :rows 2 :maxlength 1000}]
             [:button.danger {:type "submit" :aria-label (i18n/t "Remove listing {title}" {:title listing-title})}
              (i18n/t "Remove listing")]]
            [:form (merge {:method "post" :action (str "/manage/listings/" listing-id "/dismiss")} layout/once)
             [:button.secondary {:type "submit" :aria-label (i18n/t "Dismiss the reports on {title}" {:title listing-title})}
              (i18n/t "Dismiss")]]])])]
     [:div.empty [:p (i18n/t "No open reports.")]])))

(defn report-form
  "The report form on a page of its own, back with the problem in what was
  typed."
  [request {:keys [id title]} {:keys [values errors]}]
  (layout/page
   request {:title (i18n/t "Report listing")}
   [:div.board-header
    [:h1 (i18n/t "Report \u201c{title}\u201d" {:title title})]
    [:p.lede [:a {:href (str "/listings/" id)} (i18n/t "Back to the listing")]]]
   [:form.listing-form (merge {:method "post" :action (str "/listings/" id "/report") :novalidate true} layout/once)
    [:label {:for "report-reason"} (i18n/t "What is wrong with it? (optional)")]
    [:textarea (merge {:id "report-reason" :name "reason" :rows 4 :maxlength 1000}
                      (layout/invalid errors :reason))
     (:reason values)]
    (layout/field-error errors :reason)
    [:button {:type "submit"} (i18n/t "Report")]]))

(defn confirm-removal
  "Asks a moderator to confirm removing `listing`, whoever it belongs to,
  like an owner is asked, with a reason for its owner."
  [request {:keys [id title owner-name]} {:keys [values errors]}]
  (layout/page
   request {:title (i18n/t "Remove listing")}
   [:section.message
    icons/empty-box
    [:h1 (i18n/t "Remove listing?")]
    [:p (i18n/t "\u201c{title}\u201d by {owner} and its photos will be deleted, and {owner} emailed. This cannot be undone."
                {:title title :owner owner-name})]
    [:form.removal-reason (merge {:method "post" :action (str "/manage/listings/" id "/remove") :novalidate true} layout/once)
     [:label {:for "reason"} (i18n/t "Reason, emailed to the owner (optional)")]
     [:textarea (merge {:id "reason" :name "reason" :rows 3 :maxlength 1000} (layout/invalid errors :reason))
      (:reason values)]
     (layout/field-error errors :reason)
     [:div.message-actions
      [:button.danger {:type "submit"} (i18n/t "Remove listing")]
      [:a.button.secondary {:href (str "/listings/" id)} (i18n/t "Keep it")]]]]))

(defn- listings-count
  [n]
  (if (zero? n)
    (i18n/t "No listings")
    (i18n/tn "{n} listing" "{n} listings" n)))

(defn- remove-member-form
  "The form asking which member to remove, by address."
  [{:keys [values errors removed]}]
  [:form.listing-form {:method "get" :action "/manage/members/remove" :novalidate true}
   [:h2 (i18n/t "Remove a member")]
   [:label {:for "email"} (i18n/t "Their email address")]
   [:input (merge {:id "email" :name "email" :type "email" :required true :value (:email values)}
                  (layout/invalid errors :email))]
   (layout/field-error errors :email)
   [:p.field-note (i18n/t "Their account and listings are deleted for good, and they can never sign in again.")]
   (when removed [:p.field-note {:role "status"} (i18n/t "Removed.")])
   [:button.danger {:type "submit"} (i18n/t "Remove…")]])

(defn confirm-member-removal
  "Asks a moderator to confirm removing the member with address `email`."
  [request {:keys [email name listings]}]
  (layout/page
   request {:title (i18n/t "Remove a member")}
   [:section.message
    icons/empty-box
    [:h1 (i18n/t "Remove {name}?" {:name name})]
    ;; Whole sentences, with listings or without: translations read well either way.
    [:p (if (zero? listings)
          (i18n/t "{name} ({email}) and their account are deleted, and they can never sign in again. Their messages stay, from a former member. This cannot be undone."
                  {:name name :email email})
          (i18n/t "{name} ({email}) and their account are deleted, with {listings} and their photos, and they can never sign in again. Their messages stay, from a former member. This cannot be undone."
                  {:name name :email email :listings (i18n/tn "{n} listing" "{n} listings" listings)}))]
    [:form (merge {:method "post" :action "/manage/members/remove"} layout/once)
     [:input {:type "hidden" :name "email" :value email}]
     [:div.message-actions
      [:button.danger {:type "submit"} (i18n/t "Remove for good")]
      [:a.button.secondary {:href "/manage/members"} (i18n/t "Keep them")]]]]))

(defn members
  "Who may sign in: the configured patterns, those moderators added, and a
  form to add more; and a form to remove a member."
  [request {:keys [configured added values errors saved] :as form}]
  (layout/page
   request {:title (i18n/t "Who may sign in")}
   [:div.board-header
    (moderation-nav request "/manage/members")
    [:h1 (i18n/t "Who may sign in")]
    [:p.lede (i18n/t "Addresses and patterns: {person} for one person, {domain} for a domain, {subdomains} for its subdomains."
                     {:person [:code "bob@example.org"]
                      :domain [:code "*@example.org"]
                      :subdomains [:code "*@*.example.org"]})]]
   (if (= :anyone configured)
     (list
      [:div.empty
       [:p (i18n/t "Anyone can sign in: the configuration lets everyone in. Restrict it there to manage who may sign in here.")]]
      (remove-member-form form))
     (list
      [:section.members
       [:h2 (i18n/t "From the configuration")]
       [:ul.member-list (for [pattern configured] [:li [:code pattern]])]]
      [:section.members
       [:h2 (i18n/t "Added here")]
       (if (seq added)
         [:ul.member-list
          (for [{:keys [pattern added-by added-at]} added]
            [:li
             [:code pattern]
             [:span.member-meta (i18n/t "added by {who}, {date}" {:who (if (str/blank? added-by) (i18n/t "a former member") added-by)
                                                                  :date (dates/date added-at)})]
             [:form (merge {:method "post" :action "/manage/members/withdraw"} layout/once)
              [:input {:type "hidden" :name "pattern" :value pattern}]
              [:button.link-button {:type "submit" :aria-label (i18n/t "Withdraw {pattern}" {:pattern pattern})}
               (i18n/t "Withdraw")]]])]
         [:p.lede (i18n/t "Nobody yet.")])]
      [:form.listing-form (merge {:method "post" :action "/manage/members" :novalidate true} layout/once)
       [:label {:for "patterns"} (i18n/t "Add addresses or patterns")]
       [:textarea (merge {:id "patterns" :name "patterns" :rows 4 :required true
                          :placeholder "alice@gmail.com\n*@club.org"}
                         (layout/invalid errors :patterns))
        (:patterns values)]
       (layout/field-error errors :patterns)
       [:p.field-note (i18n/t "One per line, or separated by commas. Withdrawing one signs out the people only it let in, at once; their accounts stay.")]
       [:label.choice
        [:input {:type "checkbox" :name "invite" :checked (:invite values)}]
        (i18n/t "Email an invitation to the addresses")]
       (when saved [:p.field-note {:role "status"} (i18n/t "Added.")])
       [:button {:type "submit"} (i18n/t "Add")]]
      (remove-member-form form)))))

(defn- list-texts
  [which]
  (case which
    :category {:title (i18n/t "Categories")
               :lede (i18n/t "What listings are filed under, in the order members see them.")
               :add (i18n/t "Add a category")}
    :place {:title (i18n/t "Pickup places")
            :lede (i18n/t "Where members can hand things over. They can always choose Elsewhere and type a city; with no places, that is the only choice.")
            :add (i18n/t "Add a place")}))

(defn- choice-button
  "A one-button form posting to `action`, with `fields` (name -> value)."
  [action fields text aria-label]
  [:form (merge {:method "post" :action action} layout/once)
   (for [[k v] fields]
     [:input {:type "hidden" :name k :value v}])
   [:button.link-button {:type "submit" :aria-label aria-label} text]])

(defn choices
  "The page managing `which` list (:category or :place) at `page`: its
  `entries` in order, renamable, movable and retirable, with their listing
  `counts`; the retired ones, restorable; and a form to add one. `values`
  and `errors` are by entry id, `:add` for the new one."
  [request which {:keys [page entries counts values errors]}]
  (let [{:keys [title lede add]} (list-texts which)
        offered (choices/choosable entries)
        retired (filterv :retired-at entries)
        last-index (dec (count offered))]
    (layout/page
     request {:title title}
     [:div.board-header
      (moderation-nav request page)
      [:h1 title]
      [:p.lede lede]]
     [:section.members
      (if (seq offered)
        [:ol.member-list.choice-list
         (for [[i {:keys [id label]}] (map-indexed vector offered)
               :let [field (str "label-" id)
                     field-errors {field (get errors id)}]]
           [:li
            [:form.choice-rename (merge {:method "post" :action (str page "/" id "/rename") :novalidate true}
                                        layout/once)
             [:label.sr-only {:for field} (i18n/t "Name of {label}" {:label label})]
             [:input (merge {:id field :name "label" :value (get values id label)
                             :maxlength choices/max-label :required true}
                            (layout/invalid field-errors field))]
             [:button.secondary {:type "submit" :aria-label (i18n/t "Rename {label}" {:label label})} (i18n/t "Rename")]]
            [:span.member-meta (listings-count (get counts id 0))]
            [:div.choice-actions
             (when (pos? i)
               (choice-button (str page "/" id "/move") {"direction" "up"} (i18n/t "Up")
                              (i18n/t "Move {label} up" {:label label})))
             (when (< i last-index)
               (choice-button (str page "/" id "/move") {"direction" "down"} (i18n/t "Down")
                              (i18n/t "Move {label} down" {:label label})))
             (choice-button (str page "/" id "/retire") {} (i18n/t "Retire") (i18n/t "Retire {label}" {:label label}))]
            (layout/field-error field-errors field)])]
        [:p.lede (i18n/t "None offered.")])]
     (when (seq retired)
       [:section.members
        [:h2 (i18n/t "Retired")]
        [:p.lede (i18n/t "No longer offered. Their listings keep them.")]
        [:ul.member-list
         (for [{:keys [id label]} retired]
           [:li
            [:span label]
            [:span.member-meta (listings-count (get counts id 0))]
            (choice-button (str page "/" id "/restore") {} (i18n/t "Restore") (i18n/t "Restore {label}" {:label label}))])]])
     [:form.listing-form (merge {:method "post" :action page :novalidate true} layout/once)
      [:label {:for "add"} add]
      [:input (merge {:id "add" :name "label" :value (:add values) :maxlength choices/max-label :required true}
                     (layout/invalid errors :add))]
      (layout/field-error errors :add)
      [:button {:type "submit"} (i18n/t "Add")]])))

(defn appearance
  "The community's palette, each shown light and dark, to choose from."
  [request {:keys [chosen saved]}]
  (layout/page
   request {:title (i18n/t "Appearance")}
   [:div.board-header
    (moderation-nav request "/manage/appearance")
    [:h1 (i18n/t "Appearance")]
    [:p.lede (i18n/t "The colours of every page, for everyone. Each palette has a light and a dark side: members see the one they chose, or their device's.")]]
   [:form (merge {:method "post" :action "/manage/appearance"} layout/once)
    [:fieldset.palettes
     [:legend.sr-only (i18n/t "Palette")]
     (for [p palette/names]
       [:label.palette-choice
        [:input {:type "radio" :name "palette" :value p :checked (= p chosen)}]
        [:span.palette-preview {:data-palette p :aria-hidden "true"}
         (for [side ["light" "dark"]]
           [:span {:class (str "palette-side palette-" side)}
            [:span.palette-card [:span.palette-accent] [:span.palette-pop]]])]
        [:span.palette-name (palette/label p)]])]
    (when saved [:p.field-note {:role "status"} (i18n/t "Saved.")])
    [:button {:type "submit"} (i18n/t "Save")]]))

(defn poster
  "A poster for a noticeboard: the community, what it is for, and a code to
  scan that opens it at `url` (written out as `address` too), in the
  language in use, with links to print it in the others."
  [{:keys [ctx] :as request} {:keys [url address]}]
  (let [{:keys [app-name] :as settings} (:settings ctx)]
    (layout/page
     request {:title (i18n/t "Poster")}
     [:div.board-header.no-print
      (moderation-nav request "/manage/poster")
      [:h1 (i18n/t "Poster")]
      [:p.lede (i18n/t "Print it for a noticeboard, by the coffee machine or the lift: whoever scans it lands on {app-name}."
                       {:app-name app-name})]
      [:p.poster-actions
       [:button {:type "button" :data-on:click "window.print()"} (i18n/t "Print")]
       [:span (for [[l name] i18n/language-names]
                [:a {:href (str "?language=" l) :lang l} name])]]]
     ;; As payment stickers are: a block in the community's colour saying
     ;; what it is, the code framed with what scanning it does, and how.
     [:article.poster
      [:header.poster-top
       [:div.poster-brand (layout/community-mark settings)]
       [:p.poster-title (i18n/t "Give your things a {second-life} in your community."
                                {:second-life [:span.poster-highlight (i18n/t "second life")]})]
       [:p.poster-lede (i18n/t "Sell, give away or lend what you no longer need to people around you, and find what you were looking for.")]]
      [:div.poster-scan
       ;; The code's own label says it to screen readers.
       [:p.poster-scan-label {:aria-hidden "true"} (i18n/t "Scan to open {app-name}" {:app-name app-name})]
       (qr/svg url (i18n/t "Scan to open {app-name}" {:app-name app-name}) {:mark (assets/url "mark.png")})]
      [:ol.poster-steps
       [:li icons/camera [:span (i18n/t "Point your phone's camera at the code")]]
       [:li icons/tag [:span (i18n/t "Post what you no longer need, or find what you need")]]
       [:li icons/chat [:span (i18n/t "Write to each other, and meet up")]]]
      [:p.poster-address address]])))
