(ns s-exp.jumble.moderation.email
  "Plain text emails about reports: alerting moderators, telling owners."
  (:require [clojure.string :as str]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.email.mail :as mail]))

(set! *warn-on-reflection* true)

(defn- reason-lines
  [reason]
  (if (str/blank? reason) "" (str "\n\n" (mail/quoted reason))))

(defn alerts
  "Emails to each of `moderators` about the report of listing `title`, each
  in the language `language-of` gives for their address."
  [{:keys [base-url app-name moderators]} {:keys [title reporter-name reason]} language-of]
  (let [title (mail/one-line title)]
    (vec
     (for [moderator (sort moderators)]
      (i18n/speaking (language-of moderator)
      {:to moderator
       :subject (i18n/t "Listing reported: {title}" {:title title})
       :text (str (i18n/t "{reporter} reported \"{title}\"." {:reporter (mail/one-line reporter-name) :title title})
                  (reason-lines reason) "\n\n"
                  (i18n/t "Review the reports: {url}" {:url (str base-url "/manage")}) "\n\n"
                  "-- \n"
                  (i18n/t "You get this email as a moderator of {app-name}, once per reported listing."
                          {:app-name app-name})
                  "\n")})))))

(defn removal-notice
  "Email to `owner-email` saying listing `title` was removed, and why."
  [{:keys [app-name]} {:keys [owner-email title reason]}]
  (let [title (mail/one-line title)]
    {:to owner-email
     :subject (i18n/t "Your listing was removed: {title}" {:title title})
     :text (str (i18n/t "A moderator removed your listing \"{title}\" from {app-name}." {:title title :app-name app-name})
                (when-not (str/blank? reason) (str " " (i18n/t "The reason given:") (reason-lines reason)))
                "\n")}))
