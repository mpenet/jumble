(ns s-exp.jumble.web.https
  "HTTPS served by the app itself: a certificate from an ACME CA (Let's
  Encrypt), obtained and renewed in the background, and the plain HTTP
  listener answering the CA's challenges and sending the rest to HTTPS."
  (:require [clojure.tools.logging :as log]
            [ol.clave.acme.solver.http :as http-solver]
            [ol.clave.automation :as acme]
            [ol.clave.storage.file :as file-storage]
            [s-exp.enso :as enso])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file CopyOption Files LinkOption OpenOption Path StandardCopyOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.security PrivateKey)
           (java.security.cert X509Certificate)
           (java.util Base64)
           (java.util.concurrent Executors ScheduledExecutorService TimeUnit)))

(set! *warn-on-reflection* true)

(defn plain-handler
  "The plain HTTP listener's handler: the CA's challenges from `solver`,
  health checks to `handler`, and the rest redirected to `base-url`, with
  308 so that a form sent over plain HTTP is sent again rather than lost."
  [handler solver base-url]
  (http-solver/wrap-acme-challenge
   (fn [{:keys [uri query-string] :as request}]
     (if (= "/healthz" uri)
       (handler request)
       {:status 308
        :headers {"location" (cond-> (str base-url uri) query-string (str "?" query-string))}}))
   solver))

(defn event-log
  "The level and message logging certificate `event` from the automation,
  nil for routine ones: news and failures only, as the CA is reached in
  the background."
  [{:keys [type data]}]
  (let [{:keys [domain not-after error reason]} data]
    (case type
      :certificate-obtained [:info (str "Certificate obtained for " domain ", valid until " not-after)]
      :certificate-renewed [:info (str "Certificate renewed for " domain ", valid until " not-after)]
      :certificate-failed [:error (str "Certificate for " domain " failed: " error)]
      :certificate-revoked [:warn (str "Certificate for " domain " revoked: " reason)]
      :storage-error [:error (str "Certificate storage failed: " error)]
      :subscription-overflow [:warn "Certificate events dropped: no longer logged"]
      nil)))

(defn- watch-events!
  "Logs the events of `automation` from a thread of its own, until it stops,
  calling `certified` when a certificate was obtained or renewed."
  [automation certified]
  (let [events (acme/subscribe-events automation)]
    (Thread/startVirtualThread
     (fn []
       (loop []
         (let [event (.take events)]
           (when-let [[level message] (event-log event)]
             (log/log level message))
           (when (#{:certificate-obtained :certificate-renewed} (:type event))
             (try
               (certified)
               (catch Throwable t
                 (log/error t "Serving the new certificate failed"))))
           (when-not (#{:system-stopped :subscription-overflow :subscription-closed} (:type event))
             (recur))))))))

(defn- acme-automation
  "The ACME automation, not started: account and certificates kept in the
  `certificates` directory, challenges answered by `solver`."
  [{:keys [acme-directory acme-email certificates]} solver http-client]
  (acme/create (cond-> {:storage (file-storage/file-storage {:root certificates})
                        :issuers [(cond-> {:directory-url acme-directory}
                                    acme-email (assoc :email acme-email))]
                        :solvers {:http-01 solver}}
                 http-client (assoc :http-client http-client))))

(defn- pem
  [^String label ^bytes der]
  (str "-----BEGIN "
       label
       "-----\n"
       (.encodeToString (Base64/getMimeEncoder 64 (.getBytes "\n" StandardCharsets/US_ASCII)) der)
       "\n-----END "
       label
       "-----\n"))

(defn- write-atomically!
  "Writes `text` to `path` in one step, readable by its owner only: readers
  find the previous file or this one, never part of it."
  [^Path path ^String text]
  (let [owner-only (PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString "rw-------"))
        tmp (Files/createTempFile (.getParent path) "." ".tmp" (into-array FileAttribute [owner-only]))]
    (Files/writeString tmp text (make-array OpenOption 0))
    (Files/move tmp path (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))))

(defn- serving-files
  "Where the certificate being served is, for enso to read (and read again
  when it changes): PEM files in the `certificates` directory."
  [certificates]
  (let [dir (Path/of ^String certificates (into-array String ["serving"]))]
    {:dir dir
     :certificate (.resolve dir "certificate.pem")
     :key (.resolve dir "key.pem")}))

(defn- write-certificate!
  "Writes `bundle`'s chain and key where [[serving-files]] says, each file
  only when it differs from what is there."
  [{:keys [dir certificate key]} bundle]
  (Files/createDirectories dir (make-array FileAttribute 0))
  (doseq [[^Path path text] [[key (pem "PRIVATE KEY" (.getEncoded ^PrivateKey (:private-key bundle)))]
                             [certificate (apply str
                                                 (map #(pem "CERTIFICATE" (.getEncoded ^X509Certificate %))
                                                      (:certificate bundle)))]]
          :when (not (and (Files/exists path (make-array LinkOption 0))
                          (= text (Files/readString path))))]
    (write-atomically! path text)))

(defn stop
  "Stops what [[start]] started, every part even when one fails; throws the
  first failure, the others suppressed in it."
  [{:keys [^ScheduledExecutorService checks automation tls lock plain]}]
  (let [failures (into []
                       (keep (fn [step]
                               (try
                                 (step)
                                 nil
                                 (catch Throwable t
                                   t))))
                       [#(.shutdownNow checks)
                        #(acme/stop automation)
                        #(locking lock
                           (let [server @tls]
                             (reset! tls ::stopped)
                             (when (and server (not= ::stopped server))
                               (enso/stop server))))
                        #(enso/stop plain)])]
    (when-let [[^Throwable failure & others] (seq failures)]
      (doseq [other others]
        (.addSuppressed failure other))
      (throw failure))))

(defn start
  "Serves `handler` over HTTPS on `https`'s port, TCP with HTTP/2 and UDP
  with HTTP/3, and on `http-port` the plain listener of [[plain-handler]].
  The certificate for `https`'s domain comes from storage or is obtained in
  the background: HTTPS starts once there is one, and the CA is tried again
  later rather than the app stopping. A renewed certificate is written for
  enso, which serves it within seconds. Every `check-interval-ms` (an hour
  by default) the files are checked against the current certificate and
  HTTPS started if it is not yet, so that neither depends on an event
  alone; a warning says when there is still no certificate. `server-opts`
  are the enso options both listeners share; `http-client` is the one the
  CA is reached with (its default otherwise). Returns what [[stop]] takes.

  Starting signifies acceptance of the CA's Subscriber Agreement."
  [handler {:keys [host http-port base-url https server-opts http-client check-interval-ms]
            :or {check-interval-ms (* 60 60 1000)}}]
  (let [{:keys [port domain certificates]} https
        solver (http-solver/solver)
        automation (acme-automation https solver http-client)
        files (serving-files certificates)
        ;; The HTTPS server once started, ::stopped once stopped for good.
        tls (atom nil)
        lock (Object.)
        serve! (fn []
                 (when-let [bundle (acme/lookup-cert automation domain)]
                   (locking lock
                     (when-not (= ::stopped @tls)
                       (write-certificate! files bundle)
                       (when-not @tls
                         (reset! tls
                                 (enso/run-server handler
                                                  (assoc server-opts
                                                         :host host
                                                         :port port
                                                         :ssl-cert-path (str (:certificate files))
                                                         :ssl-key-path (str (:key files))
                                                         :http2 true
                                                         :http3 true)))
                         (log/info "Serving HTTPS on port" port))))))
        check! (fn []
                 (try
                   (serve!)
                   (when-not @tls
                     (log/warn "HTTPS not served yet: no certificate for" domain))
                   (catch Throwable t
                     (log/error t "Serving the certificate failed"))))
        running {:plain (enso/run-server (plain-handler handler solver base-url)
                                         (assoc server-opts :host host :port http-port))
                 :automation automation
                 :tls tls
                 :lock lock
                 :checks (Executors/newSingleThreadScheduledExecutor (.factory (Thread/ofVirtual)))}]
    (try
      (watch-events! automation serve!)
      (acme/start automation)
      (acme/manage-domains automation [domain])
      (serve!)
      (.scheduleWithFixedDelay ^ScheduledExecutorService (:checks running)
                               ^Runnable check!
                               (long check-interval-ms)
                               (long check-interval-ms)
                               TimeUnit/MILLISECONDS)
      running
      (catch Throwable t
        (try
          (stop running)
          (catch Throwable failure
            (.addSuppressed t failure)))
        (throw t)))))
