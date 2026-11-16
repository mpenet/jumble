(ns s-exp.jumble.message.view
  "Pages of conversations: writing a first message, the inbox, a thread."
  (:require [clojure.string :as str]
            [s-exp.jumble.view.dates :as dates]
            [s-exp.jumble.view.avatar :as avatar]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.view.icons :as icons]
            [s-exp.jumble.view.layout :as layout]))

(set! *warn-on-reflection* true)

(defn thread-path
  [id]
  (str "/messages/" id))

(defn- display-name
  "`name`, or `email` for people without one; neither is left of a deleted
  account."
  [name email]
  (cond
    (not (str/blank? name)) name
    (not (str/blank? email)) email
    :else (i18n/t "A former member")))

(defn- post-link
  "Link to the post a thread is about, or its title when it was removed."
  [listing-id title]
  (if listing-id
    [:a.post-chip {:href (str "/listings/" listing-id)} icons/tag title]
    [:span.post-chip.removed icons/tag (i18n/t "{title} (removed)" {:title title})]))

(defn compose
  "First message to the owner of `listing`."
  [request {:keys [id kind title owner-name]} {:keys [values errors]}]
  (layout/page request
               {:title (i18n/t "Message {name}" {:name owner-name})}
               [:div.board-header
                [:h1 (i18n/t "Message {name}" {:name owner-name})]
                [:p.lede (i18n/t "About {listing}" {:listing (post-link id title)})]]
               [:form.listing-form (merge {:method "post" :action (str "/listings/" id "/message") :novalidate true} layout/once)
                [:label {:for "subject"} (i18n/t "Subject")]
                [:input (merge {:id "subject" :name "subject" :value (:subject values) :maxlength 120}
                               (layout/invalid errors :subject))]
                (layout/field-error errors :subject)
                [:label {:for "body"} (i18n/t "Message")]
                [:textarea (merge {:id "body" :name "body" :rows 6 :maxlength 2000 :required true
                                   :placeholder (if (= "wanted" kind)
                                                  (i18n/t "Hi! I have one.")
                                                  (i18n/t "Hi! Is it still available?"))}
                                  (layout/invalid errors :body))
                 (:body values)]
                (layout/field-error errors :body)
                [:button {:type "submit"} (i18n/t "Send")]]))

(defn- snippet
  [^String s]
  (if (> (.codePointCount s 0 (count s)) 120)
    (str (subs s 0 (.offsetByCodePoints s 0 117)) "…")
    s))

(defn inbox
  [request threads]
  (layout/page request
               {:title (i18n/t "Messages")}
               [:div.board-header
                [:h1 (i18n/t "Messages")]
                [:p.lede (i18n/t "Your conversations with people in your community about their listings and yours.")]]
               (if (seq threads)
                 [:ul.threads
                  (for [{:keys [id subject other-id other-name other-email post-title listing-id last-body last-message-at unread]} threads
                        :let [other (display-name other-name other-email)]]
                    [:li
                     [:a {:class (str "thread-row" (when (pos? unread) " unread")) :href (thread-path id)}
                      (avatar/avatar {:id other-id :name other})
                      [:span.thread-main
                       [:span.thread-top
                        [:strong.thread-other other]
                        [:span.thread-time (dates/date-time last-message-at)]]
                       [:span.thread-subject subject
                        (when (or (not= subject post-title) (nil? listing-id))
                          [:span.thread-post (str " · "
                                                  (i18n/t "re: {title}"
                                                          {:title (if listing-id
                                                                    post-title
                                                                    (i18n/t "{title} (removed)" {:title post-title}))}))])]
                       [:span.thread-snippet (snippet last-body)]]
                      (when (pos? unread)
                        [:span.badge unread [:span.sr-only " " (i18n/tn "unread" "unread" unread)]])]])]
                 [:div.empty icons/mail
                  [:p (i18n/t "No messages yet. Write to someone about a listing that interests you.")]
                  [:a.button {:href "/listings"} (i18n/t "Browse listings")]])))

(defn thread
  [{:keys [user] :as request} {:keys [id subject other listing-id post-title messages]} {:keys [values errors]}]
  (layout/page request
               {:title subject}
               [:div.board-header
                [:h1 subject]
                [:p.lede (i18n/t "With {name} · {listing}"
                                 {:name [:strong (let [shown (display-name (:name other) (:email other))]
                                                   ;; A deleted account has no page.
                                                   (if (:deleted-at other)
                                                     shown
                                                     [:a {:href (str "/members/" (:id other))} shown]))]
                                  :listing (post-link listing-id post-title)})]]
               [:ol.posts
                (for [{message-id :id :keys [sender-id sender-name body created-at]} messages
                      :let [mine (= sender-id (:id user))]]
                  [:li {:id (str "m" message-id) :class (if mine "post mine" "post theirs")}
                   [:div.post-meta
                    (avatar/avatar {:id sender-id :name (if mine (:name user) (display-name sender-name (:email other)))})
                    [:strong (if mine (i18n/t "You") (display-name sender-name (:email other)))]
                    [:span (dates/date-time created-at)]]
                   [:p.post-body body]])]
               (if (:deleted-at other)
                 [:p.thread-closed (i18n/t "This member deleted their account: replies would not reach them.")]
                 [:form.reply-form (merge {:method "post" :action (thread-path id) :novalidate true} layout/once)
                  [:label.sr-only {:for "body"} (i18n/t "Reply")]
                  [:textarea (merge {:id "body" :name "body" :rows 3 :maxlength 2000 :required true
                                     :placeholder (i18n/t "Reply to {name}…" {:name (display-name (:name other) (:email other))})}
                                    (layout/invalid errors :body))
                   (:body values)]
                  (layout/field-error errors :body)
                  [:button {:type "submit"} (i18n/t "Send")]])))
