(ns s-exp.jumble.email.mail
  "Outgoing email over SMTP (STARTTLS, optional login). Emails are plain
  text maps of `:to`, `:subject` and `:text`."
  (:require [clojure.string :as str])
  (:import (jakarta.mail Message$RecipientType Session Transport)
           (jakarta.mail.internet AddressException InternetAddress MimeMessage)
           (java.util Date Properties)))

(set! *warn-on-reflection* true)

(defn one-line
  "`s` with line breaks and other control characters as single spaces: a
  header value made of it cannot carry other headers."
  [s]
  (str/trim (str/replace s #"[\p{Cc}\p{Zl}\p{Zp}\s]+" " ")))

(defn quoted
  "`text` quoted for a plain text email, each line after \"> \"."
  [text]
  (str/join "\n" (map #(str "> " %) (str/split-lines text))))

(defn failure
  "What to log about a failed send: the error's type and message with any
  email address masked, the mail server's answers quoting them."
  [^Throwable e]
  {:error (.getName (class e))
   :message (some-> (ex-message e) (str/replace #"[^\s<>@,;:\"']+@" "…@"))})

(def ^:private timeout-ms
  "Connecting, reading and writing each give up after this long, so a stuck
  server cannot hold the sender forever."
  "10000")

(defn- session
  [{:keys [host port starttls username]}]
  (Session/getInstance (doto (Properties.)
                         (.put "mail.smtp.host" host)
                         (.put "mail.smtp.port" (str port))
                         (.put "mail.smtp.auth" (str (some? username)))
                         (.put "mail.smtp.starttls.enable" (str starttls))
                         (.put "mail.smtp.starttls.required" (str starttls))
                         (.put "mail.smtp.ssl.checkserveridentity" "true")
                         (.put "mail.smtp.connectiontimeout" timeout-ms)
                         (.put "mail.smtp.timeout" timeout-ms)
                         (.put "mail.smtp.writetimeout" timeout-ms))))

(defn valid-address?
  "Whether `s` is an email address, with an optional display name."
  [s]
  (and (string? s)
       (try
         (InternetAddress. ^String s true)
         true
         (catch AddressException _
           false))))

(def ^:private plain-address-form
  "An address as people type it, without what mail servers could read as
  routing (`%`, `!`), comments or display names: dot-atom parts, an ASCII
  domain of two labels or more."
  #"[A-Za-z0-9#$&'*+/=?^_`{|}~-]+(?:\.[A-Za-z0-9#$&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+")

(defn plain-address?
  "Whether `s` is a bare email address, as typed into a sign-in form."
  [s]
  (and (string? s)
       (some? (re-matches plain-address-form s))
       (valid-address? s)))

(defn smtp-mailer
  "A mailer: a function sending an email, `{:to address :subject :text}`,
  throwing when the server does not accept it. `from` is an address, with
  an optional display name (`Jumble <jumble@example.com>`). `username` and
  `password` log in when given."
  [{:keys [^String from ^String username ^String password] :as config}]
  (let [^Session session (session config)
        from (InternetAddress. from true)]
    (fn [{:keys [^String to ^String subject ^String text]}]
      (let [message (doto (MimeMessage. session)
                      (.setFrom from)
                      (.setRecipient Message$RecipientType/TO (InternetAddress. to true))
                      (.setSubject subject "UTF-8")
                      (.setText text "UTF-8")
                      (.setSentDate (Date.)))]
        (if username
          (Transport/send message username password)
          (Transport/send message))))))
