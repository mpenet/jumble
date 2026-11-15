(ns s-exp.jumble.config
  "The configuration: the bundled defaults with the deployment's file and
  variables over them, validated and normalized at startup."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [s-exp.ex :as ex]
            [s-exp.jumble.account.membership :as membership]
            [s-exp.jumble.page.branding :as branding]
            [s-exp.jumble.error :as error]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.email.mail :as mail])
  (:import (java.net URI URISyntaxException)
           (java.time ZoneId)
           (java.util Currency)))

(set! *warn-on-reflection* true)

(def ^:private required
  [[:base-url] [:auth :method] [:auth :allowed-emails]
   [:storage :endpoint] [:storage :region] [:storage :bucket] [:storage :access-key] [:storage :secret-key]
   [:mail :host] [:mail :from]])

(def ^:private required-for-oidc
  [[:auth :issuer] [:auth :client-id] [:auth :client-secret]])

(defn- missing
  [config paths]
  (filterv #(let [v (get-in config %)] (or (nil? v) (and (string? v) (str/blank? v))))
           paths))

(def ^:private variables
  "The environment variable setting each required value."
  {[:base-url] "JUMBLE_BASE_URL"
   [:auth :method] "JUMBLE_SIGN_IN"
   [:auth :allowed-emails] "JUMBLE_ALLOWED_EMAILS"
   [:auth :issuer] "JUMBLE_OIDC_ISSUER"
   [:auth :client-id] "JUMBLE_OIDC_CLIENT_ID"
   [:auth :client-secret] "JUMBLE_OIDC_CLIENT_SECRET"
   [:storage :endpoint] "JUMBLE_S3_ENDPOINT"
   [:storage :region] "JUMBLE_S3_REGION"
   [:storage :bucket] "JUMBLE_S3_BUCKET"
   [:storage :access-key] "JUMBLE_S3_ACCESS_KEY"
   [:storage :secret-key] "JUMBLE_S3_SECRET_KEY"
   [:mail :host] "JUMBLE_SMTP_HOST"
   [:mail :from] "JUMBLE_MAIL_FROM"})

(defn- check-required
  [config paths]
  (when-let [absent (seq (missing config paths))]
    (throw (ex/ex-info (str "Missing configuration, set: " (str/join ", " (map #(variables % (pr-str %)) absent)))
                       ::error/missing-config
                       {:missing (vec absent)}))))

(defn- invalid
  [message path]
  (throw (ex/ex-info (str "Invalid configuration " path ": " message) ::error/invalid-config {:invalid path})))

(defn- local-host?
  [^String host]
  (contains? #{"localhost" "127.0.0.1" "[::1]"} host))

(defn- origin-uri
  "`s` as a URI with a scheme, a host and nothing after them but an optional
  port and slash, or nil."
  ^URI [s]
  (let [^URI uri (try (URI. s) (catch URISyntaxException _ nil))]
    (when (and uri (#{"http" "https"} (some-> (.getScheme uri) str/lower-case)) (.getHost uri)
               (nil? (.getRawUserInfo uri))
               (contains? #{nil "" "/"} (.getRawPath uri))
               (nil? (.getRawQuery uri))
               (nil? (.getRawFragment uri)))
      uri)))

(defn- origin
  "`uri` written as browsers send origins: lowercase scheme and host, the
  port only when not the scheme's default."
  [^URI uri]
  (let [scheme (str/lower-case (.getScheme uri))
        port (.getPort uri)]
    (str scheme "://" (str/lower-case (.getHost uri))
         (when-not (or (neg? port) (= port ({"http" 80 "https" 443} scheme)))
           (str ":" port)))))

(defn- check-base-url
  "Public URL of the app, its site root. https, as cookies are only Secure
  over https; plain http is tolerated for the local machine."
  [base-url]
  (let [uri (origin-uri base-url)]
    (when-not (and uri (or (= "https" (str/lower-case (.getScheme uri))) (local-host? (.getHost uri))))
      (invalid "must be an https URL of a site root (no path), or http on localhost" [:base-url]))
    (origin uri)))

(defn- check-endpoint
  "Scheme, host and optional port of the storage service; the bucket is
  configured separately."
  [endpoint]
  (if-let [uri (origin-uri endpoint)]
    (origin uri)
    (invalid "must be an http(s) URL without path" [:storage :endpoint])))

(defn- check-secure-url
  "An absolute https URL, or http to this machine, or anywhere when
  `allow-insecure` (development with containers)."
  [url path allow-insecure]
  (let [^URI uri (try (URI. url) (catch URISyntaxException _ nil))
        scheme (some-> uri .getScheme)]
    (when-not (and uri (.getHost uri)
                   (or (= "https" scheme)
                       (and (= "http" scheme) (or allow-insecure (local-host? (.getHost uri))))))
      (invalid "must be an https URL" path))
    url))

(def ^:private upstream-source
  "Where Jumble's own source is, offered to members unless an instance runs
  a modified version, whose source it must offer instead (AGPL, section 13)."
  "https://github.com/mpenet/jumble")

(defn- check-currency
  "An ISO 4217 code with a minor unit (not a pseudo-currency such as XAU)."
  [code]
  (let [^Currency currency (when (string? code)
                             (try (Currency/getInstance ^String code)
                                  (catch IllegalArgumentException _ nil)))]
    (when-not (and currency
                   (= code (.getCurrencyCode currency))
                   (<= 0 (.getDefaultFractionDigits currency)))
      (invalid "must be an ISO 4217 currency code" [:currency])))
  code)

(defn- whole-number
  "`v` as a whole number, from a number or the text of a variable; nil when
  it is neither."
  [v]
  (cond (int? v) v
        (string? v) (parse-long (str/trim v))))

(defn- check-port
  "A port number, 1 to 65535, from a number or the text of a variable."
  [v path]
  (let [port (whole-number v)]
    (when-not (and port (<= 1 port 65535))
      (invalid "must be a port number, 1 to 65535" path))
    port))

(defn- check-session-ttl
  [days]
  (when-not (and (int? days) (<= 1 days 30))
    (invalid "must be a whole number of days, 1 to 30" [:auth :session-ttl-days]))
  days)

(defn- text
  "Text setting `v` at `path`, nil when unset or blank; refused when it is
  something else, a number from the settings file say."
  [v path]
  (cond
    (nil? v) nil
    (string? v) (when-not (str/blank? v) v)
    :else (invalid "must be text" path)))

(defn- check-mail
  "SMTP settings: STARTTLS unless the server is on this machine or insecure
  connections are allowed, a valid sender, and a login with both parts or
  none (blank parts count as none)."
  [{:keys [host from starttls username password] :as mail} allow-insecure]
  (let [username (text username [:mail :username])
        password (text password [:mail :password])
        port (check-port (:port mail) [:mail :port])]
    (when (re-find #"[:/]" host)
      (invalid "must be a host name, such as smtp.example.org" [:mail :host]))
    (when (= 465 port)
      (invalid "465 (implicit TLS) is not supported: use 587, with STARTTLS" [:mail :port]))
    (when-not (or starttls allow-insecure (local-host? host))
      (invalid "must be true unless the server is local" [:mail :starttls]))
    (when-not (mail/valid-address? from)
      (invalid "must be an email address, optionally with a name: Jumble <jumble@example.com>" [:mail :from]))
    (when (not= (some? username) (some? password))
      (invalid "is needed with the other login setting" [:mail (if username :password :username)]))
    (assoc mail :username username :password password :port port)))

(defn- check-method
  "How members sign in: with an OpenID Connect provider, or by email link."
  [method]
  (when-not (#{"oidc" "email"} method)
    (invalid "must be oidc or email" [:auth :method]))
  method)

(defn- check-allowed-emails
  [patterns]
  (ex/try+
   (membership/parse patterns)
   (catch ::error/invalid-pattern _
     (invalid (ex-message &ex) [:auth :allowed-emails]))))

(defn- check-hosted-domain
  "Google accounts can be registered with any verified address, work ones
  included, and outlive leaving the organisation: with Google and an
  allowlist, the managing organisation must be checked too."
  [{:keys [issuer allowed-emails require-hosted-domain] :as auth}]
  (when (and (= "https://accounts.google.com" (str/replace (str issuer) #"/+$" ""))
             (not= :anyone allowed-emails) (not require-hosted-domain))
    (invalid "must be true with Google, unless anyone may sign in" [:auth :require-hosted-domain]))
  auth)

(defn- check-moderators
  "Email addresses of the people who handle reports, comma separated or as
  a list, as a lowercase set (empty when blank)."
  [s]
  (let [moderators (into #{}
                         (comp (map (comp str/lower-case str/trim)) (remove str/blank?))
                         (if (sequential? s) s (str/split (or s "") #",")))]
    (when-not (every? mail/plain-address? moderators)
      (invalid "must be comma separated email addresses" [:moderators]))
    moderators))

(defn- check-logo
  "The logo file at `path`, read and checked (see [[branding/read-logo]])."
  [path key]
  (when-let [path (text path [key])]
    (ex/try+
     (branding/read-logo path)
     (catch ::error/invalid-logo _
       (invalid (ex-message &ex) [key])))))

(def ^:private max-privacy-bytes (* 200 1024))

(defn- check-privacy
  "The text of the privacy file at `path`, nil when none is set."
  [path]
  (when-let [path (text path [:privacy])]
    (let [f (io/file path)]
      (when-not (and (.isFile f) (.canRead f))
        (invalid "must be a readable Markdown file" [:privacy]))
      (when (> (.length f) max-privacy-bytes)
        (invalid "must be at most 200 KB" [:privacy]))
      (slurp f))))

(defn- email-address
  "Email address setting `s` at `path`, trimmed; nil when unset."
  [s path]
  (when-let [s (some-> (text s path) str/trim)]
    (when-not (re-matches #"[^@\s]+@[^@\s]+\.[^@\s]+" s)
      (invalid "must be an email address" path))
    s))

(defn- check-contact
  "An email address for questions, nil when none is set."
  [s]
  (email-address s [:contact]))

(defn- check-listing-reminder-days
  "Days a listing goes without news before its owner is asked whether it is
  still available, 0 for never: then nothing expires either."
  [v]
  (let [days (whole-number v)]
    (when-not (and days (<= 0 days 3650))
      (invalid "must be a number of days, 0 (never) to 3650" [:listing-reminder-days]))
    days))

(defn- check-https
  "HTTPS served by the app itself, nil (off) without a port: a certificate
  for `base-url`'s host from the ACME directory, kept in the `certificates`
  directory. Its port is another than the http one, which answers the CA."
  [{:keys [port acme-directory acme-email certificates]} {:keys [base-url http]} allow-insecure]
  (when-not (or (nil? port) (and (string? port) (str/blank? port)))
    (when-not (str/starts-with? base-url "https://")
      (invalid "must be https to serve HTTPS" [:base-url]))
    {:port (let [port (check-port port [:https :port])]
             (when (= port (:port http))
               (invalid "must differ from the http port" [:https :port]))
             port)
     :domain (.getHost (URI. base-url))
     :acme-directory (check-secure-url acme-directory [:https :acme-directory] allow-insecure)
     :acme-email (email-address acme-email [:https :acme-email])
     :certificates (or (text certificates [:https :certificates])
                       (invalid "must be a directory" [:https :certificates]))}))

(defn- check-member-limit
  "The most members the community may have, none when unset: a number, or
  the text of a variable."
  [v]
  (when-not (or (nil? v) (and (string? v) (str/blank? v)))
    (let [n (whole-number v)]
      (when-not (and n (pos? n))
        (invalid "must be a whole number above 0" [:member-limit]))
      n)))

(def ^:private min-token-length 32)

(defn- check-metrics-token
  "The token metrics are read with, none (metrics off) when unset."
  [token]
  (when-let [token (text token [:metrics-token])]
    (when (< (count token) min-token-length)
      (invalid (str "must be at least " min-token-length " characters, such as from openssl rand -hex 32")
               [:metrics-token]))
    token))

(defn- check-hosted-by
  "Who hosts the instance, for the footer: a name, and optionally an https
  address. nil when unset."
  [{:keys [name url]}]
  (let [name (some-> (text name [:hosted-by :name]) str/trim)
        url (some-> (text url [:hosted-by :url]) str/trim)]
    (when (and url (not name))
      (invalid "is needed with JUMBLE_HOSTED_BY_URL" [:hosted-by :name]))
    (when (and url (not= "https" (some-> (origin-uri url) .getScheme str/lower-case)))
      (invalid "must be an https URL of a site root" [:hosted-by :url]))
    (when name {:name name :url url})))

(defn- check-timezone
  "The zone times are shown in, UTC unless set."
  [zone]
  (if-let [zone (text zone [:timezone])]
    (try (ZoneId/of zone)
         (catch java.time.DateTimeException _
           (invalid "must be a time zone such as Europe/Zurich or America/New_York" [:timezone])))
    (ZoneId/of "UTC")))

(defn- check-language
  "One of the languages Jumble speaks, English unless set."
  [language]
  (let [language (if-let [l (text language [:language])] (str/lower-case (str/trim l)) "en")]
    (when-not (i18n/languages language)
      (invalid (str "must be one of " (str/join ", " (sort i18n/languages))) [:language]))
    language))

(defn- check-app-name
  "A name on one line: it goes into email subjects."
  [s]
  (when-not (and (string? s) (not (str/blank? s)))
    (invalid "must not be blank" [:app-name]))
  (when (re-find #"[\p{Cc}\p{Zl}\p{Zp}]" s)
    (invalid "must be on one line, without control characters" [:app-name]))
  s)

(defn clients-unknown?
  "Whether normalized `config` has Jumble behind something serving its https
  address (a proxy, a load balancer) without saying so: every request then
  seems to come from it, and the limits per client are off."
  [{:keys [base-url https behind-proxy]}]
  (and (str/starts-with? base-url "https://") (nil? https) (not behind-proxy)))

(defn normalize
  "Validates `config` and returns it normalized. Throws ex-info listing the
  `:missing` paths when required values are absent or blank, or naming the
  `:invalid` one."
  [config]
  (check-required config required)
  ;; Moderators manage them in the app; a settings file listing them would
  ;; otherwise be silently ignored.
  (doseq [k [:categories :places]
          :when (contains? config k)]
    (invalid "is managed by moderators in the app: remove it from the settings file" [k]))
  (let [oidc (= "oidc" (check-method (get-in config [:auth :method])))
        allow-insecure (:allow-insecure-urls config)]
    (when oidc
      (check-required config required-for-oidc))
    (cond-> (-> config
                (update :base-url check-base-url)
                (update :app-name check-app-name)
                (update :currency check-currency)
                (update-in [:storage :endpoint] check-endpoint)
                (update-in [:storage :endpoint] check-secure-url [:storage :endpoint] allow-insecure)
                (update-in [:auth :allowed-emails] check-allowed-emails)
                (update :moderators check-moderators)
                (update :timezone check-timezone)
                (update :language check-language)
                (update-in [:http :port] check-port [:http :port])
                (update-in [:auth :session-ttl-days] check-session-ttl)
                (update :logo check-logo :logo)
                (update :logo-dark check-logo :logo-dark)
                (update :privacy check-privacy)
                (update :contact check-contact)
                (update :member-limit check-member-limit)
                (update :metrics-token check-metrics-token)
                (update :litestream-metrics-url #(some-> (text % [:litestream-metrics-url])
                                                         (check-secure-url [:litestream-metrics-url] allow-insecure)))
                (update :moderator-notice text [:moderator-notice])
                (update :notice text [:notice])
                (update :hosted-by check-hosted-by)
                (update :source-url #(check-secure-url (or (text % [:source-url]) upstream-source) [:source-url] allow-insecure))
                (update :listing-reminder-days #(check-listing-reminder-days (if (nil? %) 60 %)))
                (as-> c (update c :https check-https c allow-insecure))
                ;; Served directly, a client could forge the header a proxy would add.
                (as-> c (if (and (:behind-proxy c) (:https c))
                          (invalid "must be off when Jumble serves HTTPS itself" [:behind-proxy])
                          c))
                (update :mail check-mail allow-insecure))
      oidc (-> (update-in [:auth :issuer] check-secure-url [:auth :issuer] allow-insecure)
               (update :auth check-hosted-domain)))))

(defn load-config
  "Bundled config for `profile` with the deployment file at `path` (edn,
  nil for none) deep-merged over it. Defaults to the jumble.profile system
  property (prod when unset) and the JUMBLE_CONFIG_FILE environment variable."
  ([]
   (load-config (keyword (System/getProperty "jumble.profile" "prod"))
                (System/getenv "JUMBLE_CONFIG_FILE")))
  ([profile path]
   (let [opts {:profile profile}]
     (normalize (merge-deep (aero/read-config (io/resource "config.edn") opts)
                            (some-> path io/file (aero/read-config opts)))))))
