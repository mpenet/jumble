(ns s-exp.jumble.fixtures
  "What tests share: a migrated database, members, listings, and requests
  through the application's handler."
  (:require [clojure.string :as str]
            [next.jdbc]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.metrics :as metrics]
            [s-exp.jumble.store.migrate :as migrate]
            [s-exp.jumble.oidc :as oidc]
            [s-exp.jumble.web.routes :as routes]
            [s-exp.oda :as oda])
  (:import (java.io File)
           (java.net URLEncoder)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(defn call-with-db-path
  "Calls `f` with the path of a fresh, non-existent SQLite file and deletes
  the database files afterwards."
  [f]
  (let [dir (Files/createTempDirectory "jumble-test" (make-array FileAttribute 0))
        path (str (.resolve dir "jumble.db"))]
    (try
      (f path)
      (finally
        ;; SQLite may leave -wal, -shm or -journal files next to the database.
        (doseq [^File f (.listFiles (.toFile dir))]
          (.delete f))
        (Files/deleteIfExists dir)))))

(def ^:dynamic *db*
  "Migrated database bound by [[db-fixture]]."
  nil)

(def session-ttl (* 7 24 60 60 1000))

(def settings
  {:base-url "http://localhost:8080"
   :session-ttl session-ttl
   :sign-in "oidc"
   :allowed-emails :anyone
   :source-url "https://github.com/mpenet/jumble"
   :listing-reminder-days 30
   :app-name "Jumble"
   :currency "CHF"
   :categories [{:id "home" :label "Furniture & home"} {:id "other" :label "Other"}]
   :places [{:id "hall" :label "Main hall"}]
   :moderators #{"dave@example.com"}
   :image-origin "https://blobs.test"})

(defn use-choices!
  "Makes the categories and places of `ds`, a database without listings,
  those of [[settings]]."
  [ds]
  (doseq [[table entries] [["category" (:categories settings)] ["place" (:places settings)]]]
    (next.jdbc/execute! ds [(str "DELETE FROM " table)])
    (doseq [[i {:keys [id label]}] (map-indexed vector entries)]
      (next.jdbc/execute! ds [(str "INSERT INTO " table " (id, label, position) VALUES (?, ?, ?)") id label i]))))

(defn db-fixture
  "clojure.test fixture binding [[*db*]] to a fresh, migrated database, with
  the categories and places of [[settings]]."
  [t]
  (call-with-db-path
   (fn [path]
     (with-open [ds (db/datasource path)]
       (migrate/migrate! ds (migrate/migrations))
       (use-choices! ds)
       (binding [*db* ds]
         (t))))))

(defn user-id
  "Id of the user with `email` in [[*db*]]."
  [email]
  (:user/id (next.jdbc/execute-one! *db* ["SELECT id FROM user WHERE email = ?" email])))

(defn counted
  "The value of `series`, a metric's name and labels as the process writes
  them, in what it counts (see [[metrics/text]]); 0 before any."
  [series]
  (or (some->> (str/split-lines (metrics/text))
               (some #(when (str/starts-with? % (str series " ")) %))
               (re-find #" (\S+)$")
               second
               parse-double)
      0.0))

(defn sign-in!
  "Creates a user with `email` and a session for them in [[*db*]]. Returns
  the session token, the value of the session cookie."
  [email]
  (let [now (System/currentTimeMillis)
        user (account/upsert-user! *db* {:iss "https://dex.test" :sub email :email email :name email} now)]
    (account/create-session! *db* (:id user) now session-ttl)))

(defn user!
  "Creates user `name` (email name@example.com) in [[*db*]]; returns the id."
  [name]
  (:id (account/upsert-user! *db* {:iss "i" :sub name :email (str name "@example.com") :name name} 0)))

(defn listing!
  "Creates a listing titled `title` for `owner-id` in [[*db*]], free unless
  `kind` says otherwise. Returns it as the listing model reads it."
  [owner-id title & {:keys [kind] :or {kind "free"}}]
  (listing/by-id *db*
                 (listing/create! *db*
                                  (blob/memory-store)
                                  owner-id
                                  (:listing (listing/validate settings
                                                              {:kind kind :title title :price "5"
                                                               :category "home" :pickup "place:hall"}))
                                  []
                                  0)))

(defn handler
  "The application handler over [[*db*]]. `ctx` overrides merge over the
  defaults; the default provider is unreachable."
  ([] (handler nil))
  ([ctx]
   (routes/handler (merge-deep {:db *db*
                                :oidc (oidc/provider {:issuer "http://127.0.0.1:1/dex" :client-id "jumble"
                                                      :client-secret "s"
                                                      :redirect-uri "http://localhost:8080/auth/callback"})
                                :blobs (blob/memory-store)
                                :settings settings}
                               ctx))))

(defn request
  "Runs a request through the application handler over [[*db*]], signed in
  with session `token` when given. `form` (name -> string) is sent
  urlencoded, `json` as JSON; `ctx` overrides the handler's context, as in
  [[handler]]."
  [method uri token & {:keys [form json headers ctx]}]
  (let [[path query] (str/split uri #"\?" 2)
        body (cond
               form (.getBytes ^String (str/join "&"
                                                 (map (fn [[k v]] (str k "=" (URLEncoder/encode (str v) "UTF-8")))
                                                      form))
                               "UTF-8")
               json (.getBytes ^String (oda/write-str json) "UTF-8"))]
    ((handler ctx) (cond-> {:request-method method
                            :uri path
                            :query-string query
                            ;; Headers given win over those the body implies.
                            :headers (merge (cond-> {"origin" "http://localhost:8080"}
                                              token (assoc "cookie" (str "jumble_session=" token))
                                              form (assoc "content-type" "application/x-www-form-urlencoded")
                                              json (assoc "content-type" "application/json"))
                                            headers)}
                     body (assoc :body (java.io.ByteArrayInputStream. body))))))
