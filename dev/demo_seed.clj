(ns demo-seed
  "Fills a demo Jumble with the members and listings of dev/demo/listings.edn,
  their photos downloaded from where the file says, through the model
  functions the application uses. Members are email accounts at
  `*@<domain>`, with message emails off: their mailboxes do not exist.
  Listings a member already has, by title, are left alone, so running it
  again adds only what is missing. It marks the community as a demo, which
  dev/demo_reset.clj requires.

  Run against a deployment's own database and storage, with its settings
  in the environment and -Djumble.demo=true:

    java -Djumble.demo=true -cp jumble.jar:dev clojure.main -m demo-seed \\
      dev/demo/listings.edn jumbl.ing"
  (:require [clojure.edn :as edn]
            [next.jdbc :as jdbc]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.config :as config]
            [s-exp.jumble.listing.choices :as choices]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.store.migrate :as migrate])
  (:import (java.io File)
           (java.net URI)
           (java.nio.file Files)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse HttpResponse$BodyHandlers)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(def ^:private day (* 24 60 60 1000))

(def ^:private http
  (delay (-> (HttpClient/newBuilder)
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.connectTimeout (Duration/ofSeconds 20))
             (.build))))

(defn- download
  "The bytes at `url`. A 429 (too many requests) is tried again, after the
  wait the server asks for, up to five times; any other status but 200
  throws."
  ^bytes [^String url]
  (let [request (-> (HttpRequest/newBuilder (URI. url))
                    (.timeout (Duration/ofSeconds 60))
                    ;; As Wikimedia asks of clients: who we are, and how to reach us.
                    (.header "User-Agent" "JumbleDemoSeed/1.0 (https://jumbl.ing; info@jumbl.ing)")
                    (.build))]
    (loop [attempt 1]
      (let [^HttpResponse response (.send ^HttpClient @http request (HttpResponse$BodyHandlers/ofByteArray))
            status (.statusCode response)]
        (cond
          (= 200 status) (.body response)

          (and (= 429 status) (< attempt 5))
          (let [wait (or (some-> (.firstValue (.headers response) "retry-after") (.orElse nil) parse-long)
                         (* 10 attempt))]
            (Thread/sleep (* 1000 (long (min wait 120))))
            (recur (inc attempt)))

          :else (throw (ex-info "Download failed" {:url url :status status})))))))

(defn- webp?
  [^bytes bs]
  (and (> (alength bs) 12)
       (= "RIFF" (String. bs 0 4 "US-ASCII"))
       (= "WEBP" (String. bs 8 4 "US-ASCII"))))

(defn- as-jpeg
  "`bs` as JPEG when it is WebP, which some sources serve whatever the
  address says and Jumble does not take, converted with vips; as is
  otherwise."
  ^bytes [^bytes bs]
  (if-not (webp? bs)
    bs
    (let [^File in (File/createTempFile "demo" ".webp")
          ^File out (File/createTempFile "demo" ".jpg")
          ^java.util.List command ["vips" "copy" (str in) (str out "[Q=90]")]]
      (try
        (Files/write (.toPath in) bs ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0))
        (let [process (.start (ProcessBuilder. command))]
          (when-not (zero? (.waitFor process))
            (throw (ex-info "Converting a WebP photo failed" {}))))
        (Files/readAllBytes (.toPath out))
        (finally
          (.delete in)
          (.delete out))))))

(defn- member!
  "The demo member `handle`@`domain`, as an email sign-up named `name`, with
  message and weekly emails off: nobody reads their mailboxes."
  [ds domain {:keys [name handle]} now]
  (let [{:keys [id]} (account/email-user! ds (str handle "@" domain) now)]
    (account/set-name! ds id name)
    (account/set-notify-messages! ds id false)
    (account/set-weekly-digest! ds id false)
    id))

(defn- has-listing?
  [ds user-id title]
  (some? (jdbc/execute-one! ds ["SELECT 1 FROM listing WHERE user_id = ? AND title = ?" user-id title])))

(defn- add-listing!
  "Creates listing `entry` for `user-id`, dated `age-days` before `now`."
  [ds blobs settings user-id {:keys [kind title price category city status age-days description photos]} now]
  (let [{:keys [listing errors]} (listing/validate settings
                                                   {:kind kind :title title :description description
                                                    :price price :category category
                                                    :pickup "city" :city city})
        _ (when errors (throw (ex-info "Invalid demo listing" {:title title :errors errors})))
        prepared (listing/prepare-photos (mapv (comp as-jpeg download :url) photos))
        _ (when (:errors prepared) (throw (ex-info "Unusable demo photo" {:title title :errors (:errors prepared)})))
        created-at (- now (* age-days day) (rand-int (quot day 2)))
        id (listing/create! ds blobs user-id listing (:photos prepared) created-at)]
    (when status
      (listing/set-status! ds id user-id status (+ created-at day)))
    id))

(defn mark-demo!
  "Records that this community is a demo whose members are at `domain`:
  what the nightly reset checks before deleting anyone."
  [ds domain]
  (db/with-write-tx [tx ds]
    (jdbc/execute-one! tx
                       ["INSERT INTO community_setting (name, value) VALUES ('demo', ?)
                            ON CONFLICT (name) DO UPDATE SET value = excluded.value"
                        domain])))

(defn -main
  [data-path domain]
  (when-not (= "true" (System/getProperty "jumble.demo"))
    (binding [*out* *err*]
      (println "Refusing to seed without -Djumble.demo=true: this adds members and listings."))
    (System/exit 1))
  (let [{:keys [members listings]} (edn/read-string (slurp data-path))
        {:keys [db storage currency]} (config/load-config)
        ds (db/datasource (:path db))
        blobs (blob/s3-store storage)
        now (System/currentTimeMillis)
        _ (migrate/migrate! ds (migrate/migrations))
        _ (mark-demo! ds domain)
        settings (merge {:currency currency} (choices/all ds))
        users (mapv #(member! ds domain % now) members)
        added (reduce (fn [n {:keys [owner title] :as entry}]
                        (let [user-id (nth users owner)]
                          (if (has-listing? ds user-id title)
                            n
                            (do (add-listing! ds blobs settings user-id entry now)
                                (println "Added" title)
                                (inc n)))))
                      0
                      listings)]
    (println (count users) "members," added "listings added.")
    (shutdown-agents)))
