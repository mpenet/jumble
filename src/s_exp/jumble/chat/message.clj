(ns s-exp.jumble.chat.message
  "What the community's chat channel is told of a new post: its title, what
  it is, its category and pickup, and a link; nothing of who posted it."
  (:require [clojure.string :as str]
            [s-exp.jumble.email.mail :as mail]
            [s-exp.jumble.listing.view :as view]
            [s-exp.jumble.view.i18n :as i18n]))

(set! *warn-on-reflection* true)

(defn- inert
  "`s` as plain text in a chat message: Slack's markup characters escaped,
  as its webhooks ask, and a zero-width space after each @, so that no
  @everyone, @here, @channel or @all in what a member wrote pings anyone."
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "@" "@​")))

(defn text
  "The message announcing `listing`, in the language in use: one line, then
  the link to it, which every chat service makes a link of."
  [{:keys [base-url app-name currency categories places]} {:keys [id title category] :as listing}]
  (str (i18n/t "New on {app-name}: {title}" {:app-name (inert app-name) :title (inert (mail/one-line title))})
       " · " (view/price-label currency listing)
       " · " (inert (view/label-of categories category))
       " · " (inert (view/pickup-label places listing))
       "\n" base-url "/listings/" id))
