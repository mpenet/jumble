(ns s-exp.jumble.config-test
  "The configuration: profiles, the deployment's file, and every check on
  what it sets."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.ex :as ex]
            [s-exp.jumble.config :as config]
            [s-exp.jumble.error :as-alias error]))

(deftest dev-profile-test
  (let [{:keys [http db base-url auth app-name currency]} (config/load-config :dev nil)]
    (is (string? (:host http)))
    (is (int? (:port http)))
    (is (string? (:path db)))
    (is (= "http://localhost:8080" base-url))
    (is (= "oidc" (:method auth)))
    (is (= ["*@example.com"] (:allowed-emails auth)))
    (is (false? (:require-hosted-domain auth)))
    (is (= #{"alice@example.com"} (:moderators (config/load-config :dev nil))))
    (is (= "Jumble" app-name))
    (is (= "http://127.0.0.1:3900" (get-in (config/load-config :dev nil) [:storage :endpoint])))
    (is (= "CHF" currency))
    (testing "email goes to the docker compose Mailpit"
      (is (= {:host "127.0.0.1" :port 1025 :starttls false :from "Jumble <jumble@example.com>"
              :username nil :password nil}
             (:mail (config/load-config :dev nil)))))))

(deftest deployment-file-test
  (let [f (doto (java.io.File/createTempFile "jumble-config" ".edn") (.deleteOnExit))]
    (spit f (pr-str {:app-name "Bazaar"
                     :currency "EUR"
                     :auth {:allowed-emails "*@corp.example"}}))
    (let [config (config/load-config :dev (str f))]
      (testing "file values win"
        (is (= "Bazaar" (:app-name config)))
        (is (= "EUR" (:currency config))))
      (testing "nested maps merge: untouched keys keep their defaults"
        (is (= ["*@corp.example"] (get-in config [:auth :allowed-emails])))
        (is (= "jumble" (get-in config [:auth :client-id])))))))

(deftest missing-settings-name-variables-test
  (testing "what is missing is named as the variable to set"
    (let [e (try (config/load-config :prod nil) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (re-find #"JUMBLE_BASE_URL" (ex-message e)))
      (is (re-find #"JUMBLE_SIGN_IN" (ex-message e))))))

(deftest prod-profile-requires-settings-test
  (testing "without environment variables, production refuses to start and says why"
    (let [e (try (config/load-config :prod nil) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= ::error/missing-config (ex/ex-type e)))
      (is (= #{[:base-url] [:auth :method] [:auth :allowed-emails]
               [:storage :endpoint] [:storage :region] [:storage :bucket]
               [:storage :access-key] [:storage :secret-key] [:mail :host] [:mail :from]}
             (set (:missing (ex-data e))))))))

(def valid
  {:http {:host "0.0.0.0" :port 8080}
   :db {:path "jumble.db"}
   :base-url "https://jumble.example.com"
   :app-name "Jumble"
   :currency "CHF"
   :storage {:endpoint "https://objects.example.com" :region "eu-west-1" :bucket "jumble"
             :access-key "EXO1" :secret-key "s"}
   :auth {:method "oidc" :issuer "https://dex.example.com" :client-id "jumble" :client-secret "s"
          :allowed-emails "*@example.com" :session-ttl-days 7}
   :moderators ""
   :mail {:host "smtp.gmail.com" :port 587 :starttls true :from "Jumble <jumble@example.com>"
          :username "jumble@example.com" :password "app-password"}})

(defn- invalid-path
  [config]
  (try (config/normalize config) nil
       (catch clojure.lang.ExceptionInfo e (:invalid (ex-data e)))))

(deftest catalog-validation-test
  (testing "currency must be an ISO 4217 code"
    (is (= [:currency] (invalid-path (assoc valid :currency "XYZ"))))
    (is (= [:currency] (invalid-path (assoc valid :currency "chf"))))
    (is (= [:currency] (invalid-path (assoc valid :currency "XAU"))) "no minor unit"))
  (testing "storage endpoint is an http(s) URL without path"
    (is (= [:storage :endpoint] (invalid-path (assoc-in valid [:storage :endpoint] "objects.example.com"))))
    (is (= [:storage :endpoint] (invalid-path (assoc-in valid [:storage :endpoint] "https://storage.example.com/jumble")))))
  (testing "the identity provider and storage are reached over https"
    (is (= [:auth :issuer] (invalid-path (assoc-in valid [:auth :issuer] "http://dex.example.com"))))
    (is (= [:storage :endpoint] (invalid-path (assoc-in valid [:storage :endpoint] "http://storage.example.com"))))
    (is (nil? (invalid-path (assoc-in valid [:auth :issuer] "http://localhost:5556/dex"))) "local is fine")
    (is (nil? (invalid-path (-> valid
                                (assoc-in [:auth :issuer] "http://dex:5556/dex")
                                (assoc-in [:storage :endpoint] "http://garage:3900")
                                (assoc :allow-insecure-urls true))))
        "unless explicitly allowed, for local containers"))
  (testing "email is sent over STARTTLS, except to the local machine or when allowed"
    (is (= [:mail :starttls] (invalid-path (assoc-in valid [:mail :starttls] false))))
    (is (nil? (invalid-path (-> valid (assoc-in [:mail :starttls] false) (assoc-in [:mail :host] "localhost")))))
    (is (nil? (invalid-path (-> valid (assoc-in [:mail :starttls] false) (assoc :allow-insecure-urls true))))))
  (testing "the sender is a valid address"
    (is (= [:mail :from] (invalid-path (assoc-in valid [:mail :from] "Jumble <not an address>")))))
  (testing "a login needs both username and password"
    (is (= [:mail :password] (invalid-path (assoc-in valid [:mail :password] nil))))
    (is (nil? (invalid-path (update valid :mail assoc :username "" :password "")))
        "blank environment variables mean no login"))
  (testing "with Google and an allowlist, the organisation managing the account is checked too"
    (let [google (assoc-in valid [:auth :issuer] "https://accounts.google.com")]
      (is (= [:auth :require-hosted-domain] (invalid-path google)))
      (is (= [:auth :require-hosted-domain] (invalid-path (assoc-in google [:auth :issuer] "https://accounts.google.com/"))))
      (is (nil? (invalid-path (assoc-in google [:auth :require-hosted-domain] true))))
      (is (nil? (invalid-path (assoc-in google [:auth :allowed-emails] "*"))))))
  (testing "sign-in is by OIDC or by email, one of them"
    (is (= [:auth :method] (invalid-path (assoc-in valid [:auth :method] "both")))))
  (testing "signing in by email needs no OIDC provider"
    (is (nil? (invalid-path (update valid :auth #(-> % (assoc :method "email") (dissoc :issuer :client-secret)))))))
  (testing "malformed email patterns are refused"
    (is (= [:auth :allowed-emails] (invalid-path (assoc-in valid [:auth :allowed-emails] "*@*"))))
    (is (= [:auth :allowed-emails] (invalid-path (assoc-in valid [:auth :allowed-emails] " , ")))))
  (testing "a logo must be a readable SVG or PNG; none is fine"
    (is (= [:logo] (invalid-path (assoc valid :logo "/nonexistent/logo.svg"))))
    (is (= [:logo-dark] (invalid-path (assoc valid :logo-dark "/nonexistent/logo.svg"))))
    (is (nil? (invalid-path (assoc valid :logo ""))) "a blank variable means none"))
  (testing "the time zone is a known one, UTC unless set"
    (is (= [:timezone] (invalid-path (assoc valid :timezone "Mars/Olympus"))))
    (is (= (java.time.ZoneId/of "Europe/Zurich") (:timezone (config/normalize (assoc valid :timezone "Europe/Zurich")))))
    (is (= (java.time.ZoneId/of "UTC") (:timezone (config/normalize valid)))))
  (testing "categories and places are not settings: a file setting them is refused"
    (is (= [:categories] (invalid-path (assoc valid :categories [{:id "a" :label "A"}]))))
    (is (= [:places] (invalid-path (assoc valid :places [])))))
  (testing "a privacy text file must be readable Markdown of reasonable size; none is fine"
    (is (= [:privacy] (invalid-path (assoc valid :privacy "/nonexistent/privacy.md"))))
    (is (nil? (:privacy (config/normalize (assoc valid :privacy "")))))
    (let [f (doto (java.io.File/createTempFile "privacy" ".md") (.deleteOnExit))]
      (spit f "# Our rules")
      (is (= "# Our rules" (:privacy (config/normalize (assoc valid :privacy (str f))))))))
  (testing "the contact is an email address; none is fine"
    (is (= [:contact] (invalid-path (assoc valid :contact "not an address"))))
    (is (= "privacy@club.org" (:contact (config/normalize (assoc valid :contact " privacy@club.org ")))))
    (is (nil? (:contact (config/normalize valid)))))
  (testing "language: one Jumble speaks, English unless set"
    (is (= "en" (:language (config/normalize valid))))
    (is (= "fr" (:language (config/normalize (assoc valid :language "fr")))))
    (is (= [:language] (invalid-path (assoc valid :language "klingon")))))
  (testing "sessions last 1 to 30 days"
    (doseq [bad [0 31 "7" nil]]
      (is (= [:auth :session-ttl-days] (invalid-path (assoc-in valid [:auth :session-ttl-days] bad))) (pr-str bad))))
  (testing "ports are numbers from 1 to 65535, typed or from variables"
    (is (= 8080 (get-in (config/normalize (assoc-in valid [:http :port] "8080")) [:http :port])))
    (doseq [bad ["" "eighty" "0" "99999"]]
      (is (= [:http :port] (invalid-path (assoc-in valid [:http :port] bad))) (pr-str bad)))
    (is (= [:mail :port] (invalid-path (assoc-in valid [:mail :port] "")))))
  (testing "SMTP: no implicit TLS port, a host and not a URL"
    (is (= [:mail :port] (invalid-path (assoc-in valid [:mail :port] 465))))
    (is (= [:mail :host] (invalid-path (assoc-in valid [:mail :host] "smtp://smtp.example.org")))))
  (testing "moderators can be a list in a settings file"
    (is (= #{"dave@example.org"} (:moderators (config/normalize (assoc valid :moderators ["Dave@example.org"]))))))
  (testing "moderators are addresses as people type them"
    (doseq [bad ["<dave@example.org>" "dave%x@example.org" "dave@localhost"]]
      (is (= [:moderators] (invalid-path (assoc valid :moderators bad))) bad)))
  (testing "the source offered is upstream's unless set, and a web address"
    (is (= "https://github.com/mpenet/jumble" (:source-url (config/normalize valid))))
    (is (= "https://git.example.org/ours" (:source-url (config/normalize (assoc valid :source-url "https://git.example.org/ours")))))
    (is (= [:source-url] (invalid-path (assoc valid :source-url "ftp://example.org/x")))))
  (testing "app name must not be blank, nor span lines"
    (is (= [:app-name] (invalid-path (assoc valid :app-name ""))))
    (is (= [:app-name] (invalid-path (assoc valid :app-name "Market\nBcc: x@example.org"))))))

(deftest normalize-test
  (testing "allowed emails: patterns, comma separated or as a list"
    (is (= ["*@example.com" "bob@gmail.com"]
           (get-in (config/normalize (assoc-in valid [:auth :allowed-emails] " *@Example.com,bob@gmail.com "))
                   [:auth :allowed-emails])))
    (is (= ["*@example.com"]
           (get-in (config/normalize (assoc-in valid [:auth :allowed-emails] ["*@example.com"]))
                   [:auth :allowed-emails]))))
  (testing "* lets anyone in"
    (is (= :anyone (get-in (config/normalize (assoc-in valid [:auth :allowed-emails] "*"))
                           [:auth :allowed-emails]))))
  (testing "OIDC settings are required when signing in with OIDC"
    (is (= [[:auth :issuer]] (:missing (ex-data (try (config/normalize (update valid :auth dissoc :issuer))
                                                     (catch clojure.lang.ExceptionInfo e e)))))))
  (testing "blank counts as missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing configuration"
                          (config/normalize (assoc-in valid [:auth :client-secret] " ")))))

  (testing "an invalid setting is named, as an invalid configuration"
    (let [e (try (config/normalize (assoc valid :base-url "jumble.example.com")) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= ::error/invalid-config (ex/ex-type e)))
      (is (= [:base-url] (:invalid (ex-data e))))))
  (testing "base-url must be https, except on the local machine"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"base-url"
                          (config/normalize (assoc valid :base-url "http://jumble.example.com"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"base-url"
                          (config/normalize (assoc valid :base-url "jumble.example.com"))))
    (is (map? (config/normalize (assoc valid :base-url "http://localhost:8080"))))
    (is (map? (config/normalize (assoc valid :base-url "http://127.0.0.1:8080")))))
  (testing "moderators: comma separated addresses, case and spacing ignored, none by default"
    (is (= #{"alice@example.com" "bob@example.com"}
           (:moderators (config/normalize (assoc valid :moderators " Alice@Example.com,bob@example.com ")))))
    (is (= #{} (:moderators (config/normalize valid))))
    (is (= [:moderators] (invalid-path (assoc valid :moderators "alice")))))
  (testing "addresses are written as browsers send them: lowercase host, no default port"
    (is (= "https://jumble.example.com" (:base-url (config/normalize (assoc valid :base-url "HTTPS://Jumble.Example.com:443/")))))
    (is (= "https://jumble.example.com:8443" (:base-url (config/normalize (assoc valid :base-url "https://jumble.example.com:8443")))))
    (is (= "https://objects.example.com"
           (get-in (config/normalize (assoc-in valid [:storage :endpoint] "https://Objects.example.com:443")) [:storage :endpoint]))))
  (testing "Jumble lives at the root of its address"
    (doseq [bad ["https://example.com/jumble" "https://example.com/?x=1" "https://user:pw@example.com"]]
      (is (= [:base-url] (invalid-path (assoc valid :base-url bad))) bad)))
  (testing "base-url loses a trailing slash"
    (is (= "https://jumble.example.com"
           (:base-url (config/normalize (assoc valid :base-url "https://jumble.example.com/")))))))

(deftest hosting-settings-test
  (testing "the member limit is a positive whole number; none by default"
    (is (nil? (:member-limit (config/normalize valid))))
    (is (nil? (:member-limit (config/normalize (assoc valid :member-limit "")))))
    (is (= 20 (:member-limit (config/normalize (assoc valid :member-limit " 20 ")))))
    (is (= [:member-limit] (invalid-path (assoc valid :member-limit "0"))))
    (is (= [:member-limit] (invalid-path (assoc valid :member-limit "twenty")))))
  (testing "the metrics token is long enough not to be guessed; none turns metrics off"
    (is (nil? (:metrics-token (config/normalize (assoc valid :metrics-token "")))))
    (is (= [:metrics-token] (invalid-path (assoc valid :metrics-token "secret"))))
    (is (= (apply str (repeat 32 "a")) (:metrics-token (config/normalize (assoc valid :metrics-token (apply str (repeat 32 "a"))))))))
  (testing "the notice to everyone is a file, which may come and go"
    (is (nil? (:notice (config/normalize (assoc valid :notice "")))))
    (is (= "/etc/jumble/demo.md" (:notice (config/normalize (assoc valid :notice "/etc/jumble/demo.md"))))))
  (testing "the moderator notice is a file, which may come and go"
    (is (nil? (:moderator-notice (config/normalize (assoc valid :moderator-notice "")))))
    (is (= "/etc/jumble/notice.md" (:moderator-notice (config/normalize (assoc valid :moderator-notice "/etc/jumble/notice.md"))))))
  (testing "who hosts the instance: a name, with an https address optionally"
    (is (nil? (:hosted-by (config/normalize (assoc valid :hosted-by {:name "" :url ""})))))
    (is (= {:name "Jumble" :url "https://jumbl.ing"}
           (:hosted-by (config/normalize (assoc valid :hosted-by {:name " Jumble " :url "https://jumbl.ing"})))))
    (is (= {:name "Jumble" :url nil} (:hosted-by (config/normalize (assoc valid :hosted-by {:name "Jumble"})))))
    (is (= [:hosted-by :url] (invalid-path (assoc valid :hosted-by {:name "Jumble" :url "jumbl.ing"}))))
    (is (= [:hosted-by :name] (invalid-path (assoc valid :hosted-by {:url "https://jumbl.ing"}))))))

(deftest https-test
  (let [acme "https://acme-v02.api.letsencrypt.org/directory"
        https {:port nil :acme-directory acme :acme-email nil :certificates "certificates"}]
    (testing "off without a port"
      (is (nil? (:https (config/normalize (assoc valid :https https)))))
      (is (nil? (:https (config/normalize (assoc valid :https (assoc https :port "")))))))
    (testing "behind a proxy, or serving HTTPS itself, where clients could forge their address: not both"
      (is (= [:behind-proxy] (invalid-path (assoc valid :behind-proxy true :https (assoc https :port "8443")))))
      (is (true? (:behind-proxy (config/normalize (assoc valid :behind-proxy true :https https))))))
    (testing "on with a port: base-url's host gets a certificate from the ACME directory"
      (is (= {:port 8443 :domain "jumble.example.com" :acme-directory acme :acme-email nil
              :certificates "certificates"}
             (:https (config/normalize (assoc valid :https (assoc https :port "8443")))))))
    (testing "an email for the CA, optionally"
      (is (= "ops@example.com"
             (get-in (config/normalize (assoc valid :https (assoc https :port 8443 :acme-email " ops@example.com ")))
                     [:https :acme-email])))
      (is (= [:https :acme-email] (invalid-path (assoc valid :https (assoc https :port 8443 :acme-email "ops"))))))
    (testing "refused when not usable"
      (is (= [:https :port] (invalid-path (assoc valid :https (assoc https :port "0")))))
      (is (= [:https :acme-directory]
             (invalid-path (assoc valid :https (assoc https :port 8443 :acme-directory "http://acme.example.com/dir")))))
      (is (= [:https :certificates] (invalid-path (assoc valid :https (assoc https :port 8443 :certificates " ")))))
      (is (= [:https :port] (invalid-path (assoc valid :https (assoc https :port "8080"))))
          "the http port, which answers the CA")
      (is (= [:base-url] (invalid-path (assoc valid :base-url "http://localhost:8080"
                                              :https (assoc https :port 8443))))
          "plain http has no certificate to get"))))

(deftest chat-webhook-url-test
  (testing "new posts are announced to a chat webhook when one is set: Slack's, Mattermost's, Discord's"
    (is (nil? (:chat-webhook-url (config/normalize valid))))
    (is (nil? (:chat-webhook-url (config/normalize (assoc valid :chat-webhook-url " ")))))
    (is (= "https://hooks.slack.com/services/T0/B0/x"
           (:chat-webhook-url (config/normalize (assoc valid :chat-webhook-url "https://hooks.slack.com/services/T0/B0/x"))))))
  (testing "over https only: the address is a secret"
    (is (= [:chat-webhook-url] (invalid-path (assoc valid :chat-webhook-url "http://hooks.example.org/x"))))
    (is (= [:chat-webhook-url] (invalid-path (assoc valid :chat-webhook-url "not a url"))))))

(deftest irc-url-test
  (let [irc #(:irc (config/normalize (assoc valid :irc-url %)))]
    (testing "new posts are announced on an IRC channel when one is set, by a bot staying in it"
      (is (nil? (:irc (config/normalize valid))))
      (is (nil? (irc " ")))
      (is (= {:host "irc.example.org" :port 6697 :tls true :nick "jumble" :password "s3cr:t@x" :channel "#swap" :key nil}
             (irc "ircs://jumble:s3cr%3At%40x@irc.example.org/#swap")))
      (is (= {:host "irc.example.org" :port 7000 :tls true :nick "jumble" :password nil :channel "#swap" :key "door"}
             (irc "ircs://jumble@irc.example.org:7000/#swap?key=door"))))
    (testing "channels as networks name them, the scheme in any case, an empty password as none"
      (is (= "##linux" (:channel (irc "ircs://jumble@irc.example.org/##linux"))))
      (is (= {:channel "##linux" :key "door"} (select-keys (irc "ircs://jumble@irc.example.org/##linux?key=door") [:channel :key])))
      (is (= "#swap" (:channel (irc "IRCS://jumble@irc.example.org/#swap"))))
      (is (nil? (:password (irc "ircs://jumble:@irc.example.org/#swap")))))
    (testing "a key that would end the JOIN line, or add to it, is refused"
      (doseq [url ["ircs://jumble@irc.example.org/#swap?key=a%0D%0AQUIT" "ircs://jumble@irc.example.org/#swap?key=a%20b"
                   "ircs://jumble@irc.example.org/#swap?key=a,b"]]
        (is (= [:irc-url] (invalid-path (assoc valid :irc-url url))) url)))
    (testing "over TLS, with a nick and a channel"
      (doseq [url ["irc://jumble@irc.example.org/#swap" "ircs://irc.example.org/#swap" "ircs://jumble@irc.example.org/"
                   "ircs://jumble@irc.example.org/#bad,channel" "not a url"]]
        (is (= [:irc-url] (invalid-path (assoc valid :irc-url url))) url)))))

(deftest litestream-metrics-url-test
  (testing "where Litestream serves its metrics, on the same machine; off when unset"
    (is (nil? (:litestream-metrics-url (config/normalize valid))))
    (is (= "http://127.0.0.1:9090/metrics"
           (:litestream-metrics-url (config/normalize (assoc valid :litestream-metrics-url "http://127.0.0.1:9090/metrics")))))
    (is (= [:litestream-metrics-url] (invalid-path (assoc valid :litestream-metrics-url "not a url"))))))

(deftest listing-reminder-days-test
  (testing "owners are asked after 60 days without news by default; 0 never"
    (is (= 60 (:listing-reminder-days (config/normalize valid))))
    (is (= 0 (:listing-reminder-days (config/normalize (assoc valid :listing-reminder-days "0")))))
    (is (= 90 (:listing-reminder-days (config/normalize (assoc valid :listing-reminder-days " 90 "))))))
  (testing "refused when not a number of days"
    (is (= [:listing-reminder-days] (invalid-path (assoc valid :listing-reminder-days "-1"))))
    (is (= [:listing-reminder-days] (invalid-path (assoc valid :listing-reminder-days "soon"))))
    (is (= [:listing-reminder-days] (invalid-path (assoc valid :listing-reminder-days "4000"))))))

(deftest setting-types-test
  (testing "a number where one is meant is taken as given, as from the settings file"
    (is (= 50 (:member-limit (config/normalize (assoc valid :member-limit 50)))))
    (is (= 50 (:member-limit (config/normalize (assoc valid :member-limit "50"))))))
  (testing "anything else where text is meant is refused, naming the setting"
    (doseq [[path v] [[[:member-limit] :fifty]
                      [[:metrics-token] 123]
                      [[:timezone] :utc]
                      [[:language] 1]
                      [[:contact] 1]
                      [[:moderator-notice] 1]
                      [[:notice] 1]
                      [[:chat-webhook-url] 1]
                      [[:irc-url] 1]
                      [[:logo] 1]
                      [[:privacy] 1]
                      [[:hosted-by :name] 1]
                      [[:mail :username] 1]]]
      (is (= path (invalid-path (assoc-in valid path v))) (pr-str path v)))))

(deftest client-limits-test
  (testing "a public https address without Jumble serving it, nor a proxy declared: clients cannot be told apart"
    (is (config/clients-unknown? {:base-url "https://market.example.org"}))
    (is (not (config/clients-unknown? {:base-url "https://market.example.org" :https {:port 8443}})))
    (is (not (config/clients-unknown? {:base-url "https://market.example.org" :behind-proxy true})))
    (is (not (config/clients-unknown? {:base-url "http://localhost:8080"})) "development, alone on the machine")))
