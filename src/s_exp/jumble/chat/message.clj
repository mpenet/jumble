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

(defn- summary
  "`listing` in one line, in the language in use, as members wrote it."
  [{:keys [app-name currency categories places]} {:keys [title category] :as listing}]
  (str (i18n/t "New on {app-name}: {title}" {:app-name app-name :title (mail/one-line title)})
       " · " (view/price-label currency listing)
       " · " (view/label-of categories category)
       " · " (view/pickup-label places listing)))

(defn- link
  [{:keys [base-url]} {:keys [id]}]
  (str base-url "/listings/" id))

(defn text
  "The webhook's message announcing `listing`: its summary, made inert, then
  the link to it, which every chat service makes a link of."
  [settings listing]
  (str (inert (summary settings listing)) "\n" (link settings listing)))

(defn irc-line
  "IRC's message announcing `listing`: its summary and the link on one
  line; made safe to send by the bot."
  [settings listing]
  (str (summary settings listing) " · " (link settings listing)))
