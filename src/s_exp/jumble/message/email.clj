(ns s-exp.jumble.message.email
  "Plain text email telling someone about unread messages."
  (:require [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.email.mail :as mail]
            [s-exp.jumble.message.view :as view]))

(set! *warn-on-reflection* true)

(defn notification
  "Email about `unread` messages in thread `thread-id`, the `latest` of them
  quoted."
  [{:keys [base-url app-name]} {:keys [thread-id to subject sender-name unread latest]}]
  (let [values {:name (mail/one-line sender-name) :subject (mail/one-line subject)}]
    {:to to
     :subject (i18n/tn "New message from {name}: {subject}" "New messages from {name}: {subject}" unread values)
     :text (str (i18n/tn "{name} sent you a message about \"{subject}\":"
                         "{name} sent you {n} messages about \"{subject}\". The latest:"
                         unread values)
                "\n\n" (mail/quoted latest) "\n\n"
                (i18n/t "Read and reply: {url}" {:url (str base-url (view/thread-path thread-id))}) "\n\n"
                "-- \n"
                (i18n/t "You get this email from {app-name} when a message stays unread for a few minutes."
                        {:app-name app-name})
                "\n"
                (i18n/t "To stop these emails: {url}" {:url (str base-url "/settings")})
                "\n")}))
