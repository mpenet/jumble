(ns s-exp.jumble.chat.message
  "What the community's chat channel is told of a new post: its title, what
  it is, its category and pickup, and a link; nothing of who posted it."
  (:require [clojure.string :as str]
            [s-exp.jumble.email.mail :as mail]
            [s-exp.jumble.irc.protocol :as irc]
            [s-exp.jumble.listing.view :as view]
            [s-exp.jumble.view.i18n :as i18n]))

(set! *warn-on-reflection* true)

(def ^:private zero-width-space "\u200B")

(defn- inert
  "`s` as plain text in a chat message: Slack's markup characters escaped,
  as its webhooks ask; a zero-width space after each @, so that no
  @everyone, @here, @channel or @all in what a member wrote pings anyone;
  and inside Markdown's link and image syntax, which Mattermost and
  Discord would show as a link of any text or load an image from anywhere."
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "@" (str "@" zero-width-space))
      (str/replace "](" (str "]" zero-width-space "("))
      (str/replace "![" (str "!" zero-width-space "["))))

(defn- summary
  "`listing` in one line, in the language in use, as members wrote it but
  for line breaks, and the characters turning the text's direction, which
  could show the line, link included, other than it is."
  [{:keys [app-name currency categories places]} {:keys [title category] :as listing}]
  (-> (str (i18n/t "New on {app-name}: {title}" {:app-name app-name :title title})
           " · " (view/price-label currency listing)
           " · " (view/label-of categories category)
           " · " (view/pickup-label places listing))
      mail/one-line
      (str/replace #"[\u202A-\u202E\u2066-\u2069\u200E\u200F\u061C]" "")))

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
  line, made safe to send; the summary cut to what the link leaves of
  a line, the link always whole."
  [settings listing]
  (let [tail (str " · " (link settings listing))
        room (- irc/max-text-bytes (alength (.getBytes tail java.nio.charset.StandardCharsets/UTF_8)))]
    (str (irc/fit (irc/safe-text (summary settings listing)) room) tail)))
