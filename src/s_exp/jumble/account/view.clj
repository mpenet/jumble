(ns s-exp.jumble.account.view
  "Pages of a member's account: settings, deleting it, and signing in by
  email."
  (:require [s-exp.jumble.page.assets :as assets]
            [s-exp.jumble.view.dates :as dates]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.view.icons :as icons]
            [s-exp.jumble.view.layout :as layout]))

(defn- notifications
  "Browser notifications on this device, turned on and off by push.js
  (hidden without it), and the devices they go to."
  [{:keys [ctx]} {:keys [devices application-key]}]
  (let [app-name (get-in ctx [:settings :app-name])]
    [:section#push.notifications {:data-application-key application-key}
     [:h2 (i18n/t "Notifications on this device")]
     [:p (i18n/t "Get a notification here as soon as someone writes to you, even with {app-name} closed."
                 {:app-name app-name})]
     [:p
      [:button {:type "button" :data-push-on true :hidden true} (i18n/t "Notify me on this device")]
      [:button.secondary {:type "button" :data-push-off true :hidden true} (i18n/t "Stop on this device")]]
     [:p.field-note {:data-push-unsupported true :hidden true} (i18n/t "This browser cannot show notifications.")]
     [:p.field-error {:data-push-failed true :hidden true :role "alert"} (i18n/t "Something went wrong")]
     [:p.field-note {:data-push-blocked true :hidden true}
      (i18n/t "Notifications are blocked for this site: allow them in the browser's settings, then try again.")]
     [:p.field-note (i18n/t "On iPhone and iPad, add {app-name} to your home screen first, then turn notifications on from there."
                            {:app-name app-name})]
     (when (seq devices)
       (list [:h3 (i18n/t "Devices notified")]
             [:ul.device-list
              (for [{:keys [id created-at key]} devices]
                [:li {:data-device key}
                 [:span {:id (str "device-" id)} (i18n/t "Added {date}" {:date (dates/date created-at)})]
                 [:span.badge {:data-push-this true :hidden true} (i18n/t "This device")]
                 [:form (merge {:method "post" :action (str "/settings/devices/" id "/delete")} layout/once)
                  [:button.link-button {:type "submit" :aria-describedby (str "device-" id)} (i18n/t "Remove")]]])]))
     [:script {:type "module" :src (assets/url "push.js")}]]))

(set! *warn-on-reflection* true)

(defn settings
  [{:keys [user] :as request} {:keys [notify-messages weekly-digest language name-editable values errors saved] :as data}]
  (layout/page
   request {:title (i18n/t "Settings")}
   [:div.board-header
    [:h1 (i18n/t "Settings")]
    [:p.lede (i18n/t "Signed in as {email}" {:email (:email user)})]]
   [:form.listing-form (merge {:method "post" :action "/settings" :novalidate true} layout/once)
    (when name-editable
      (list [:label {:for "name"} (i18n/t "Name")]
            [:input (merge {:id "name" :name "name" :value (:name values) :maxlength 80 :required true
                            :autocomplete "name"}
                           (layout/invalid errors :name))]
            (layout/field-error errors :name)
            [:p.field-note (i18n/t "Shown to other members on your listings and messages.")]))
    [:label {:for "language"} (i18n/t "Language")]
    [:select {:id "language" :name "language"}
     [:option {:value "" :selected (nil? language)}
      (i18n/t "The community's ({language})" {:language (i18n/language-name (:language i18n/*community-catalog* "en"))})]
     (for [[l name] i18n/language-names]
       [:option {:value l :selected (= l language)} name])]
    [:fieldset
     [:legend (i18n/t "Email")]
     [:label.choice
      [:input {:type "checkbox" :name "notify_messages" :checked notify-messages}]
      (i18n/t "Email me when a message stays unread for a few minutes")]
     [:label.choice
      [:input {:type "checkbox" :name "weekly_digest" :checked weekly-digest}]
      (i18n/t "Email me once a week what is new on the board")]]
    (when saved [:p.field-note {:role "status"} (i18n/t "Saved.")])
    [:button {:type "submit"} (i18n/t "Save")]]
   (notifications request data)
   [:section.your-data
    [:h2 (i18n/t "Your data")]
    [:p (i18n/t "{download-link} — your account, listings, favorites, conversations and reports, as a JSON file."
                {:download-link [:a {:href "/settings/export"} (i18n/t "Download your data")]})]
    [:p [:a.danger-link {:href "/settings/delete"} (i18n/t "Delete your account")]]]))

(defn confirm-delete
  "Asks the user to confirm deleting their account, saying what happens."
  [request]
  (layout/page
   request {:title (i18n/t "Delete your account")}
   [:section.message
    icons/empty-box
    [:h1 (i18n/t "Delete your account?")]
    [:p (i18n/t "Your listings and their photos, your favorites and your settings will be deleted, and you will be signed out. Messages you sent stay with the people you wrote to, from \"A former member\". This cannot be undone.")]
    [:p (i18n/t "{download-link} first, if you want to keep a copy."
                {:download-link [:a {:href "/settings/export"} (i18n/t "Download your data")]})]
    [:div.message-actions
     [:form (merge {:method "post" :action "/settings/delete"} layout/once)
      [:button.danger {:type "submit"} (i18n/t "Delete my account")]]
     [:a.button.secondary {:href "/settings"} (i18n/t "Keep it")]]]))

(defn email-sign-in
  "Asks for the email address to send a sign-in link to."
  [request {:keys [values errors]}]
  (layout/page
   request {:title (i18n/t "Sign in")}
   [:section.message
    icons/mail
    [:h1 (i18n/t "Sign in")]
    [:p (i18n/t "Enter your email address and we will send you a link to sign in.")]
    [:form.sign-in-form (merge {:method "post" :action "/login" :novalidate true} layout/once)
     [:input {:type "hidden" :name "return_to" :value (:return-to values)}]
     [:label.sr-only {:for "email"} (i18n/t "Email address")]
     [:input (merge {:id "email" :type "email" :name "email" :value (:email values) :autocomplete "email"
                     :required true :placeholder (i18n/t "you@example.org")}
                    (layout/invalid errors :email))]
     (layout/field-error errors :email)
     [:button {:type "submit"} (i18n/t "Email me a link")]]]))

(defn check-inbox
  "What anyone asking for a link is told, member or not."
  [request minutes]
  (layout/page
   request {:title (i18n/t "Check your inbox")}
   [:section.message
    icons/mail
    [:h1 (i18n/t "Check your inbox")]
    [:p (i18n/tn "If that address may sign in here, a link to sign in is on its way. It works once, within {n} minute."
                 "If that address may sign in here, a link to sign in is on its way. It works once, within {n} minutes."
                 minutes)]
    [:div.message-actions
     [:a.button.secondary {:href "/login"} (i18n/t "Use another address")]]]))

(defn confirm-email-sign-in
  "Asks to confirm signing in with the link's token. A page, not an
  immediate sign-in: mail scanners follow links, and would use them up."
  [request email token]
  (layout/page
   request {:title (i18n/t "Sign in")}
   [:section.message
    icons/mail
    [:h1 (i18n/t "Sign in")]
    [:p (i18n/t "Sign in as {email}?" {:email [:strong email]})]
    [:form (merge {:method "post" :action "/login/email"} layout/once)
     [:input {:type "hidden" :name "token" :value token}]
     [:div.message-actions
      [:button {:type "submit"} (i18n/t "Sign in")]]]]))
