(ns s-exp.jumble
  "The system: the database, the web server and email delivery, built from
  the configuration, and the main that runs it until stopped."
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.config :as config]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.email.delivery :as delivery]
            [s-exp.jumble.irc.bot :as bot]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.email.mail :as mail]
            [s-exp.jumble.oidc :as oidc]
            [s-exp.jumble.web.server :as server]
            [taoensso.trove :as trove]
            [taoensso.trove.tools-logging :as trove-logging])
  (:import (org.slf4j.bridge SLF4JBridgeHandler)
           (sun.misc Signal SignalHandler))
  (:gen-class))

(set! *warn-on-reflection* true)

(def ^:private day (* 24 60 60 1000))

(defn system
  [{:req! {db-config :db http-config :http base-url :base-url auth-config :auth
           app-name :app-name currency :currency
           storage :storage mail-config :mail moderators :moderators}
    :keys [https behind-proxy source-url notice chat-webhook-url irc logo logo-dark timezone language privacy contact member-limit metrics-token litestream-metrics-url moderator-notice hosted-by
           listing-reminder-days]}]
  (let [{:keys! [method allowed-emails session-ttl-days] :keys [issuer client-id client-secret]} auth-config
        catalog (i18n/catalog (or language "en"))
        blobs (blob/s3-store storage)]
    (component/system-map
     :db (db/component (assoc db-config :pinned {"currency" currency}))
     :server (component/using (server/component (assoc http-config
                                                       :https https
                                                       :settings {:base-url base-url
                                                                  :session-ttl (* session-ttl-days day)
                                                                  :sign-in method
                                                                  :allowed-emails allowed-emails
                                                                  ;; Serving HTTPS itself, Jumble sees each client's address;
                                                                  ;; behind a proxy, the proxy says it.
                                                                  :behind-proxy behind-proxy
                                                                  :clients-known (or (some? https) behind-proxy)
                                                                  :require-hosted-domain (boolean (:require-hosted-domain auth-config))
                                                                  :app-name app-name
                                                                  :currency currency
                                                                  :moderators moderators
                                                                  :timezone timezone
                                                                  :catalog catalog
                                                                  :logo logo
                                                                  :logo-dark logo-dark
                                                                  :privacy privacy
                                                                  :contact contact
                                                                  :member-limit member-limit
                                                                  :metrics-token metrics-token
                                                                  :litestream-metrics-url litestream-metrics-url
                                                                  :moderator-notice moderator-notice
                                                                  :notice notice
                                                                  ;; Whether new posts are announced, for the privacy
                                                                  ;; page; the channels' secrets stay with the loop.
                                                                  :chat-announced (boolean (or chat-webhook-url irc))
                                                                  :hosted-by hosted-by
                                                                  :source-url source-url
                                                                  :image-origin (:endpoint storage)}
                                                       :blobs blobs
                                                       :oidc (when (= "oidc" method)
                                                               (oidc/provider {:issuer issuer
                                                                               :client-id client-id
                                                                               :client-secret client-secret
                                                                               :redirect-uri (str base-url "/auth/callback")}))))
                              [:db])
     :delivery (component/using (delivery/component {:mailer (mail/smtp-mailer mail-config)
                                                     :blobs blobs
                                                     :settings {:base-url base-url :app-name app-name :catalog catalog
                                                                :allowed-emails allowed-emails
                                                                :listing-reminder-days listing-reminder-days
                                                                :currency currency
                                                                :chat-webhook-url chat-webhook-url
                                                                :irc irc}
                                                     :interval-ms 30000})
                                [:db :irc])
     :irc (bot/component irc))))

(defn -main
  [& _]
  ;; enso logs through java.util.logging; route it to logback.
  (SLF4JBridgeHandler/removeHandlersForRootLogger)
  (SLF4JBridgeHandler/install)
  ;; clave logs through Trove; route it to tools.logging.
  (trove/set-log-fn! (trove-logging/get-log-fn))
  (let [system (try
                 (let [config (config/load-config)]
                   (when (and (:allow-insecure-urls config) (not= "dev" (System/getProperty "jumble.profile")))
                     (log/warn "JUMBLE_ALLOW_INSECURE_URLS is on: plain http is accepted for sign-in, storage"
                               "and email beyond this machine. Meant for development only."))
                   (when (config/clients-unknown? config)
                     (log/warn "JUMBLE_BASE_URL is https but Jumble does not serve it: behind a proxy, set"
                               "JUMBLE_BEHIND_PROXY=true, or the limits per client (sign-in links, sign-ins) are off."))
                   (component/start (system config)))
                 (catch Throwable t
                   (log/error t "Failed to start")
                   (System/exit 1)))]
    (let [stop (delay (log/info "Stopping")
                      (component/stop system))]
      ;; SIGTERM and SIGINT are a requested stop: exit 0 once stopped. The
      ;; JVM's default (128 + signal) reads as a crash to supervisors, and
      ;; Litestream, which runs the app, then skips its orderly shutdown.
      (doseq [signal ["TERM" "INT"]]
        (Signal/handle (Signal. ^String signal)
                       (reify SignalHandler
                         (handle [_ _]
                           ;; Exit even when stopping fails, or the process
                           ;; would stay up until killed.
                           (System/exit (try
                                          @stop
                                          0
                                          (catch Throwable t
                                            (log/error t "Stopping failed")
                                            1)))))))
      ;; Any other exit still stops the system.
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] @stop))))
    (log/info "Started")
    @(promise)))
