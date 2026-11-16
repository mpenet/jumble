(ns s-exp.jumble.page.privacy
  "The privacy page, readable by anyone: the community's own text when it
  set one (JUMBLE_PRIVACY_FILE, Markdown), else Jumble's, which says what
  is kept and why, who sees it, the cookies, how long, and how to take it
  away."
  (:require [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.view.layout :as layout]
            [s-exp.jumble.view.markdown :as markdown]
            [s-exp.jumble.web.response :as response]))

(set! *warn-on-reflection* true)

(defn- section
  [heading & paragraphs]
  [:section
   [:h2 heading]
   (for [p paragraphs :when p] [:p p])])

(defn- default-text
  [{:keys [app-name contact chat-webhook-url]}]
  (list
   [:h1 (i18n/t "Privacy")]
   [:p.lede (i18n/t "{app-name} is run by and for its community. This page says what it keeps about you, and why."
                    {:app-name app-name})]
   (section (i18n/t "What is kept")
            (i18n/t "Your name and email address, from signing in; what you post: listings, their photos and your messages; your favorites, saved searches and settings; and reports you make."))
   (section (i18n/t "Why")
            (i18n/t "Only to run {app-name}: to show your listings, deliver your messages and send you emails: those you asked for, and a weekly summary of what is new, which you can turn off. Nothing is sold or shared for advertising, and there is no tracking and no analytics."
                    {:app-name app-name}))
   (section (i18n/t "Who sees what")
            (i18n/t "Other members see your name, your listings and what you write to them. Messages are between you and the person you write to. Moderators see reports, and can remove listings."))
   (section (i18n/t "Cookies")
            (i18n/t "Only those {app-name} needs: one keeps you signed in, one remembers your light or dark theme, and one protects signing in. No tracking cookies."
                    {:app-name app-name}))
   (section (i18n/t "Services involved")
            (i18n/t "Photos and backups are stored with your community's storage provider, emails go through its email provider, and signing in may go through its sign-in service. Notifications you turn on go through your browser's push service (Google's, Apple's, Mozilla's or Microsoft's), encrypted: it sees only when one is sent.")
            (when chat-webhook-url
              (i18n/t "New listings are also announced in your community's chat channel: their title, price, category and pickup place, with a link; not who posted them.")))
   (section (i18n/t "How long")
            (i18n/t "Until you remove it, or delete your account; then the messages you wrote stay with those you wrote to, and your reports with the moderators, without your name. Backups keep deleted data for a while, usually up to three months."))
   (section (i18n/t "Your choices")
            (i18n/t "Download everything {app-name} keeps about you, or delete your account, from {settings}."
                    {:app-name app-name :settings [:a {:href "/settings"} (i18n/t "Settings")]}))
   (section (i18n/t "Questions")
            (if contact
              (i18n/t "Write to {contact}." {:contact [:a {:href (str "mailto:" contact)} contact]})
              (i18n/t "Ask your community's moderators.")))))

(defn page
  [{:keys [ctx] :as request}]
  (let [{:keys [privacy] :as settings} (:settings ctx)]
    (response/html 200 (layout/page request {:title (i18n/t "Privacy")}
                                    [:article.privacy
                                     (or (markdown/render privacy) (default-text settings))]))))

(def routes
  {[:get "/privacy"] #'page})
