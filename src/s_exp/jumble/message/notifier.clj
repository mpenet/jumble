(ns s-exp.jumble.message.notifier
  "Sends the emails about unread messages as they fall due."
  (:require [clojure.tools.logging :as log]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.email.mail :as mail]
            [s-exp.jumble.message.email :as email]
            [s-exp.jumble.message.notification :as notification]
            [s-exp.jumble.email.outbox :as outbox]
            [s-exp.jumble.metrics :as metrics]
            [s-exp.jumble.view.i18n :as i18n]))

(set! *warn-on-reflection* true)

(def ^:private batch-size 100)

(defn- deliver!
  "Handles one due notification; true when an email went out. Someone no
  longer `allowed` in is not emailed: theirs is dropped."
  [db mailer settings allowed pending now]
  (let [{:keys [send postpone]} (notification/decide db pending now)]
    (cond
      (and send (not (allowed (:to send)))) (do (notification/drop! db pending) false)

      send
      (if-let [error (try
                       (mailer (i18n/speaking (:language send) (email/notification settings send)))
                       nil
                       (catch Exception e
                         e))]
        (let [outcome (notification/failed! db pending now)]
          (metrics/inc! outbox/emails {:result "failed"})
          (log/warn "Message email failed"
                    (merge {:thread-id (:thread-id pending) :outcome outcome} (mail/failure error)))
          false)
        (do (metrics/inc! outbox/emails {:result "sent"})
            (outbox/record-sent! #(notification/sent! db pending now))
            true))

      postpone (do (notification/postpone! db pending postpone) false)
      :else (do (notification/drop! db pending) false))))

(defn deliver-due!
  "Handles a batch of the notifications due at `now`, one by one while
  `running` returns true; one failing leaves the others unaffected. Emails
  are sent outside of any transaction. Returns how many went out."
  [db mailer settings now running]
  (let [allowed (account/allowed db (:allowed-emails settings))]
    (outbox/deliver-each (notification/due db now batch-size)
                         running
                         #(deliver! db mailer settings allowed % now)
                         #(select-keys % [:thread-id]))))
