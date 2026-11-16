(ns s-exp.jumble.email.delivery
  "Sends due emails from a background thread: notifications of unread
  messages and the outbox, which also gets the questions to owners of
  listings without news (see [[freshness/sweep!]]), the daily news for
  saved searches (see [[saved-searches/send-due!]]) and the weekly email of
  what is new (see [[weekly/send-due!]]); sends browser notifications (see
  [[push/deliver-due!]]); announces new posts on the chat channels (see
  [[chat/deliver-webhook!]], [[chat/deliver-irc!]]); and deletes the photo
  objects due (see
  [[listing/delete-due-objects!]])."
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.account.data :as data]
            [s-exp.jumble.listing.freshness :as freshness]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.listing.saved-searches :as saved-searches]
            [s-exp.jumble.listing.weekly :as weekly]
            [s-exp.jumble.metrics :as metrics]
            [s-exp.jumble.message.notifier :as notifier]
            [s-exp.jumble.http :as http]
            [s-exp.jumble.push.delivery :as push]
            [s-exp.jumble.chat.delivery :as chat]
            [s-exp.jumble.irc.bot :as bot]
            [s-exp.jumble.email.outbox :as outbox])
  (:import (java.util.concurrent Executors ScheduledExecutorService TimeUnit)))

(set! *warn-on-reflection* true)

(def ^:private runs
  (metrics/counter "jumble_background_runs_total" "Runs of each background step, by result: ok or failed."))

(def ^:private last-success
  (metrics/gauge "jumble_background_last_success_seconds"
                 "When each background step last ran without failing, in seconds since the epoch."))

(defn- run-step
  "Runs `f`, one step of a delivery round: a failure is logged as `step`'s,
  leaving the other steps to run, in this round and the next. Each run is
  counted, by its result."
  [step f]
  (try
    (f)
    (metrics/inc! runs {:step step :result "ok"})
    (metrics/set-gauge! last-success {:step step} (quot (System/currentTimeMillis) 1000))
    (catch Throwable t
      (metrics/inc! runs {:step step :result "failed"})
      (log/error t "Delivery step failed:" step))))

(defrecord Delivery [db irc mailer blobs settings interval-ms]
  component/Lifecycle
  (start [this]
    (let [executor (Executors/newSingleThreadScheduledExecutor (.factory (.name (Thread/ofVirtual) "jumble-delivery")))
          running #(not (.isShutdown executor))
          ;; Push services and the chat webhook.
          client (http/client {:connect-timeout 5000 :timeout 10000})
          send-request #(http/request client % :string)
          say (when-let [b (:bot irc)] #(bot/say! b %))]
      (.scheduleWithFixedDelay executor
                               (fn []
                                 ;; An exception escaping here would cancel all later runs:
                                 ;; each step catches its own.
                                 (binding [i18n/*catalog* (:catalog settings)
                                           i18n/*community-catalog* (:catalog settings)]
                                   (let [ds (:datasource db)
                                         now (System/currentTimeMillis)]
                                     ;; In order, none started once stopping.
                                     (doseq [[step f] [["notifications" #(notifier/deliver-due! ds mailer settings now running)]
                                                       ["freshness" #(freshness/sweep! ds settings now)]
                                                       ["saved searches" #(saved-searches/send-due! ds settings now)]
                                                       ["weekly digest" #(weekly/send-due! ds settings now)]
                                                       ["outbox" #(outbox/deliver-due! ds mailer now running)]
                                                       ["push" #(push/deliver-due! ds send-request settings now running)]
                                                       ["chat" #(chat/deliver-webhook! ds send-request settings now running)]
                                                       ["irc" #(chat/deliver-irc! ds say settings now running)]
                                                       ["storage" #(listing/delete-due-objects! ds blobs now)]
                                                       ["deletions" #(data/finish-deletions! ds blobs now)]]
                                             :while (running)]
                                       (run-step step f)))))
                               (long interval-ms)
                               (long interval-ms)
                               TimeUnit/MILLISECONDS)
      (assoc this :executor executor)))
  (stop [this]
    ;; A run in progress finishes the email at hand and stops.
    (when-let [^ScheduledExecutorService executor (:executor this)]
      (.shutdown executor)
      (when-not (.awaitTermination executor 30 TimeUnit/SECONDS)
        (log/warn "Stopped while an email was being sent; it may be sent again")))
    (assoc this :executor nil)))

(defn component
  "Delivers due emails every `interval-ms`, and deletes the photo objects due
  from `blobs`. Uses the `:db` component, and `:irc`'s bot to announce new
  posts on IRC."
  [{:keys! [mailer blobs settings interval-ms]}]
  (map->Delivery {:mailer mailer :blobs blobs :settings settings :interval-ms interval-ms}))
