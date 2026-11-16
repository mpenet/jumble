(ns s-exp.jumble.listing.model
  "Listings: validation, photos, statuses, the board and full-text search,
  favorites, and their storage in the database and the object store."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.ex :as ex]
            [s-exp.jumble.metrics :as metrics]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.error :as error]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.image :as image]
            [s-exp.jumble.listing.choices :as choices]
            [s-exp.jumble.money :as money]
            [s-exp.jumble.email.outbox :as outbox]
            [s-exp.jumble.web.params :as params]
            [s-exp.jumble.token :as token])
  (:import (java.util.concurrent Semaphore)))

(set! *warn-on-reflection* true)

(def kinds
  "What a listing is for: something to sell, give away or lend, or wanted,
  a post of what someone looks for."
  #{"sale" "free" "lend" "wanted"})

(def max-photos 5)

(defn- check
  "Adds `message` under `field` to `errors` unless `ok`."
  [errors ok field message]
  (cond-> errors (not ok) (assoc field message)))

(def ^:private place-prefix "place:")

(defn place-pickup
  "Form value choosing pickup at place `id`."
  [id]
  (str place-prefix id))

(defn pickup-value
  "Form value of a listing's pickup."
  [{:keys [pickup-place]}]
  (if pickup-place (place-pickup pickup-place) "city"))

(defn default-values
  "Form values of a new listing: for sale, picked up at the first place
  offered (in a city when there are none)."
  [{:keys [places]}]
  {:kind "sale"
   :pickup (if-let [{:keys [id]} (first (choices/choosable places))] (place-pickup id) "city")})

(defn- parse-pickup
  "`place:<id>` for a place in `places`, or `city`."
  [places pickup]
  (cond
    (= "city" pickup) {:city true}
    (and (string? pickup) (str/starts-with? pickup place-prefix))
    (let [id (subs pickup (count place-prefix))]
      (when (some #(= id (:id %)) places) {:place id}))))

(defn validate
  "Validates form input (strings, possibly nil) against the deployment's
  `settings`: its currency, and the categories and places offered. Returns
  `{:listing normalized}` or `{:errors {field message}}`."
  [{:keys [currency categories places]} {:keys [kind title description price category pickup city]}]
  (let [categories (choices/choosable categories)
        places (choices/choosable places)
        title (params/trimmed title)
        description (or (params/trimmed description) "")
        sale (= "sale" kind)
        price-minor (when sale (money/parse currency price))
        pickup (parse-pickup places pickup)
        city (params/trimmed city)
        errors (-> {}
                   (check (kinds kind) :kind (i18n/t "Choose whether you sell, give away, lend or look for it."))
                   (check (seq title) :title (i18n/t "Give it a title."))
                   (check (<= (count title) 120) :title (i18n/t "Keep the title to 120 characters or fewer."))
                   (check (<= (count description) 5000) :description (i18n/t "Keep the description to 5000 characters or fewer."))
                   (check (or (not sale) price-minor) :price (i18n/t "Enter a price of at most one million, such as 25 or 12.50."))
                   (check (some #(= category (:id %)) categories) :category (i18n/t "Choose a category."))
                   (check pickup :pickup (i18n/t "Choose where to pick it up."))
                   (check (or (not (:city pickup)) (seq city)) :city (i18n/t "Enter the city."))
                   (check (or (not (:city pickup)) (<= (count city) 80)) :city (i18n/t "Keep the city to 80 characters or fewer.")))]
    (if (seq errors)
      {:errors errors}
      {:listing {:kind kind
                 :title title
                 :description description
                 :price-minor price-minor
                 :category category
                 :pickup-place (:place pickup)
                 :pickup-city (when (:city pickup) city)}})))

(defn- too-many-photos
  []
  {:errors {:photos (i18n/t "At most {max} photos." {:max max-photos})}})

(defn- photo-problem
  "What is wrong with photo `number`, refused with an error of type `t`."
  [number t]
  (case t
    ::error/too-many-pixels
    (i18n/t "Photo {number} is too large (over {megapixels} megapixels)."
            {:number number :megapixels (quot image/max-pixels 1000000)})
    ::error/too-slow
    (i18n/t "Photo {number} took too long to process. Try a smaller one." {:number number})
    ::error/images-busy
    (i18n/t "Too many photos are being processed right now. Please try again in a moment.")
    (i18n/t "Photo {number} is not a JPEG, PNG, HEIC or AVIF image." {:number number})))

(defn prepare-photos
  "Processes uploaded image bytes, in order, into their stored versions.
  Returns `{:photos [...]}` or `{:errors {:photos message}}`."
  [uploads]
  (if (> (count uploads) max-photos)
    (too-many-photos)
    (reduce (fn [acc [i bs]]
              (try
                (update acc :photos conj (image/process bs))
                (catch clojure.lang.ExceptionInfo e
                  (reduced {:errors {:photos (photo-problem (inc i) (ex/ex-type e))}}))))
            {:photos []}
            (map-indexed vector uploads))))

(def max-uploads
  "Uploads taken in at once, over all members: each is held in memory while
  it is read and its photos processed."
  4)

(defonce ^:private upload-slots (Semaphore. max-uploads))

(defonce ^:private uploading
  ;; Members with an upload under way.
  (atom #{}))

(defn with-upload-turn
  "Calls `(f)`, an upload of member `user-id`'s, when it has a turn: one of
  theirs at a time, as processing is shared by everyone, and at most
  [[max-uploads]] over everyone. Without one, returns `(busy)`: decided
  before the upload is read, so one turned away costs nothing."
  [user-id f busy]
  (let [[before] (swap-vals! uploading conj user-id)]
    (if (contains? before user-id)
      (busy)
      (try
        (if (.tryAcquire ^Semaphore upload-slots)
          (try
            (f)
            (finally
              (.release ^Semaphore upload-slots)))
          (busy))
        (finally
          (swap! uploading disj user-id))))))

(defn photo-key
  "Object key of a photo's stored `size` (\"full\" or \"thumb\")."
  [object-key size]
  (str object-key "/" size ".jpg"))

(defn- upload!
  "Stores both sizes of `photo` under `object-key`."
  [blobs object-key {:keys [full thumb]}]
  (blob/put! blobs (photo-key object-key "full") (:bytes full) "image/jpeg")
  (blob/put! blobs (photo-key object-key "thumb") (:bytes thumb) "image/jpeg"))

(defn- delete-objects-quietly!
  "Deletes the stored objects of `object-keys`, both sizes each, logging
  failures instead of throwing, one failing leaving the others to go.
  Returns the object keys whose objects are all gone."
  [blobs object-keys]
  (filterv (fn [object-key]
             (every? true? (mapv (fn [size]
                                   (let [k (photo-key object-key size)]
                                     (try
                                       (blob/delete! blobs k)
                                       true
                                       (catch Exception e
                                         (log/warn e "Could not delete photo object" k)
                                         false))))
                                 ["full" "thumb"])))
           object-keys))

(def ^:private upload-grace
  "How long uploaded objects wait for their listing to be recorded, after
  which they are deleted (see [[delete-due-objects!]])."
  (* 60 60 1000))

(defn- queue-deletions!
  "Queues the objects of `object-keys` for deletion at `due-at`, in `tx`."
  [tx object-keys due-at]
  (doseq [object-key object-keys]
    (jdbc/execute-one! tx ["INSERT INTO object_deletion (object_key, due_at) VALUES (?, ?)
                            ON CONFLICT (object_key) DO UPDATE SET due_at = min(due_at, excluded.due_at)"
                           object-key due-at])))

(defn- unqueue-deletions!
  "Takes the objects of `object-keys` off the deletion queue, in `tx`: kept,
  or deleted."
  [tx object-keys]
  (doseq [object-key object-keys]
    (jdbc/execute-one! tx ["DELETE FROM object_deletion WHERE object_key = ?" object-key])))

(defn- delete-objects!
  "Deletes the queued objects of `object-keys` now. Those deleted leave the
  queue; the others stay on it for [[delete-due-objects!]]."
  [db blobs object-keys]
  (when-let [deleted (seq (delete-objects-quietly! blobs object-keys))]
    (try
      (db/with-write-tx [tx db]
        (unqueue-deletions! tx deleted))
      ;; Left queued, they are deleted again later, which succeeds.
      (catch Exception e
        (log/warn e "Could not take deleted photo objects off the queue")))))

(defn pending-deletions
  "Photo objects queued for deletion from storage, due or not."
  [db]
  (:n (jdbc/execute-one! db ["SELECT count(*) AS n FROM object_deletion"] db/opts)))

(defn delete-due-objects!
  "Deletes the queued objects due at `now`, a batch: those of removed photos
  storage could not delete at once, and uploads whose listing was never
  recorded. One failing is tried again later, after a delay doubling up to
  an hour (see [[outbox/retry-at]]), until it goes."
  [db blobs now]
  (doseq [{:keys [object-key attempts]} (jdbc/execute! db ["SELECT object_key, attempts FROM object_deletion
                                                            WHERE due_at <= ? ORDER BY due_at LIMIT 100"
                                                           now]
                                                       db/opts)]
    (if (seq (delete-objects-quietly! blobs [object-key]))
      (db/with-write-tx [tx db]
        (unqueue-deletions! tx [object-key]))
      (db/with-write-tx [tx db]
        (jdbc/execute-one! tx ["UPDATE object_deletion SET attempts = ?, due_at = ? WHERE object_key = ?"
                               (inc attempts) (outbox/retry-at (inc attempts) now) object-key])))))

(defn photo-urls
  "Function of an object key and size (\"full\" or \"thumb\") to the URL
  of that photo, all valid from `now`."
  [blobs now]
  (fn [object-key size]
    (blob/url blobs (photo-key object-key size) now)))

(defn- with-uploads
  "Uploads `photos`, then calls `f` with their object keys, which must take
  them off the deletion queue in the transaction recording them (see
  [[unqueue-deletions!]]). They are queued first, due [[upload-grace]] after
  `now`, so they are deleted even should the process die before; when
  anything fails, they are deleted at once."
  [db blobs photos now f]
  ;; Known before storing anything, so a half-stored photo is deleted too.
  (let [object-keys (vec (repeatedly (count photos) #(str "photos/" (token/generate))))]
    (when (seq object-keys)
      (db/with-write-tx [tx db]
        (queue-deletions! tx object-keys (+ now upload-grace))))
    (try
      (doseq [[object-key photo] (map vector object-keys photos)]
        (upload! blobs object-key photo))
      (f object-keys)
      (catch Throwable t
        (delete-objects! db blobs object-keys)
        (throw t)))))

(defn create!
  "Stores a new listing for `user-id` with its prepared `photos` and returns
  its id, or nil when the account is being deleted. Photos are uploaded
  first; if the listing is then not recorded, they are deleted again."
  [db blobs user-id {:keys [kind title description price-minor category pickup-place pickup-city]} photos now]
  (try
    (with-uploads
      db blobs photos now
      (fn [object-keys]
        (db/with-write-tx [tx db]
          (let [{:keys [id]} (or (jdbc/execute-one! tx ["INSERT INTO listing (user_id, kind, title, description, price_minor,
                                                                              category, pickup_place, pickup_city,
                                                                              created_at, updated_at, fresh_at)
                                                         SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                                                         WHERE EXISTS (SELECT 1 FROM user WHERE id = ? AND deleted_at IS NULL)
                                                         RETURNING id"
                                                        user-id kind title description price-minor
                                                        category pickup-place pickup-city now now now user-id]
                                                    db/opts)
                                 (throw (ex/ex-info "The owner deleted their account" ::error/owner-gone)))]
            (doseq [[position object-key] (map-indexed vector object-keys)
                    :let [{:keys [width height]} (:full (nth photos position))]]
              (jdbc/execute-one! tx ["INSERT INTO photo (listing_id, position, object_key, width, height)
                                      VALUES (?, ?, ?, ?, ?)"
                                     id position object-key width height]))
            (unqueue-deletions! tx object-keys)
            id))))
    (catch clojure.lang.ExceptionInfo e
      (when-not (= ::error/owner-gone (ex/ex-type e))
        (throw e)))))

(defn- photo-rows
  "Photos of listing `id`, in order."
  [connectable id]
  (jdbc/execute! connectable ["SELECT id, position, object_key, width, height FROM photo
                               WHERE listing_id = ? ORDER BY position" id]
                 db/opts))

(defn by-id
  "The listing `id` with its owner and photos (in order), or nil."
  [db id]
  (when-let [row (jdbc/execute-one! db ["SELECT l.id, l.user_id, l.kind, l.title, l.description, l.price_minor, l.category,
                                                l.pickup_place, l.pickup_city, l.status, l.created_at, l.updated_at,
                                                l.reminded_at, u.name AS owner_name, u.email AS owner_email
                                         FROM listing l JOIN user u ON u.id = l.user_id
                                         WHERE l.id = ?" id]
                                    db/opts)]
    (assoc row :photos (photo-rows db id))))

(def on-board
  "Statuses of listings on the board: available, or reserved for someone
  but not handed over yet. They can be messaged about and edited."
  #{"active" "reserved"})

(def on-board-sql
  "[[on-board]] in SQL, as the board's partial index is written."
  "('active', 'reserved')")

(def closed
  (metrics/counter "jumble_listings_closed_total"
                   "Listings that left the board, by outcome: sold, given, found, expired, deleted by their owner or removed by a moderator."
                   (for [outcome ["sold" "given" "found" "expired" "deleted" "removed"]] {:outcome outcome})))

(def settle-time
  "Longer than a listing takes to be saved, photos uploaded: emails about
  new listings name those older than this, so none is missed being saved
  as they are written."
  (* 5 60 1000))

(def ^:private transitions
  "Status changes an owner can make, from -> to, in the order they are
  offered. Expired listings are put back by their owner; only freshness
  checks expire them."
  {"active" ["reserved" "sold" "given" "found"]
   "reserved" ["active" "sold" "given"]
   "sold" ["active"]
   "given" ["active"]
   "found" ["active"]
   "expired" ["active"]})

(defn- move-allowed?
  "Sold applies to sales only, given to free items only, found to wanted
  posts only, which are not reserved: nothing is promised to anyone."
  [kind status]
  (case status
    "sold" (= "sale" kind)
    "given" (= "free" kind)
    "found" (= "wanted" kind)
    "reserved" (not= "wanted" kind)
    true))

(defn status-moves
  "Statuses the owner can move `listing` to."
  [{:keys [status kind]}]
  (filter #(move-allowed? kind %) (transitions status)))

(defn set-status!
  "Changes the status of listing `id`, owned by `user-id`, to `status`. Only
  the moves in [[transitions]] are made, as [[move-allowed?]] says for the
  listing's kind. The owner's attention makes the listing fresh
  (see [[confirm-fresh!]]). True when the listing changed."
  [db id user-id status now]
  (let [from (vec (keep (fn [[from tos]] (when (some #{status} tos) from)) transitions))]
    (boolean
     (when (seq from)
       (db/with-write-tx [tx db]
         (jdbc/execute-one! tx (into [(str "UPDATE listing SET status = ?1, updated_at = ?2, fresh_at = ?2,
                                                                 reminded_at = NULL, reminder_give_ups = 0
                                            WHERE id = ?3 AND user_id = ?4
                                              AND (?1 <> 'sold' OR kind = 'sale')
                                              AND (?1 <> 'given' OR kind = 'free')
                                              AND (?1 <> 'found' OR kind = 'wanted')
                                              AND (?1 <> 'reserved' OR kind <> 'wanted')
                                              AND status IN ("
                                           (str/join ", " (map #(str "?" (+ 5 %)) (range (count from))))
                                           ") RETURNING id")
                                      status now id user-id]
                                     from)))))))

(defn confirm-fresh!
  "Records that listing `id` on the board, owned by `user-id`, is still
  available: its owner answered the question. True when it is such a
  listing."
  [db id user-id now]
  (db/with-write-tx [tx db]
    (some? (jdbc/execute-one! tx [(str "UPDATE listing SET fresh_at = ?, reminded_at = NULL, reminder_give_ups = 0
                                        WHERE id = ? AND user_id = ? AND status IN " on-board-sql "
                                        RETURNING id")
                                  now id user-id]))))

(defn- delete-in-tx!
  "Deletes listing `id` and its photo rows in `tx`, queueing their objects
  for deletion; returns the photos' object keys, for deleting once
  committed."
  [tx id]
  (let [object-keys (mapv :object-key (jdbc/execute! tx ["SELECT object_key FROM photo WHERE listing_id = ?" id]
                                                     db/opts))]
    (jdbc/execute-one! tx ["DELETE FROM listing WHERE id = ?" id])
    (queue-deletions! tx object-keys 0)
    object-keys))

(defn- deleting!
  "Runs `(f tx)` in a write transaction; when it returns object keys, deletes
  those objects once committed, those storage cannot delete then left to
  [[delete-due-objects!]]: never a listing without its photos."
  [db blobs f]
  (let [object-keys (db/with-write-tx [tx db] (f tx))]
    (some->> object-keys (delete-objects! db blobs))
    object-keys))

(defn delete!
  "Removes listing `id` owned by `user-id`, with its photos and their stored
  objects. True when there was such a listing."
  [db blobs id user-id]
  (some? (deleting! db blobs (fn [tx]
                               (when (jdbc/execute-one! tx ["SELECT 1 FROM listing WHERE id = ? AND user_id = ?"
                                                            id user-id])
                                 (delete-in-tx! tx id))))))

(defn remove!
  "Removes listing `id` whoever owns it, as moderation does. `(f tx listing)`
  runs first in the same transaction, `listing` holding its `:title` and
  `:user-id`. True when there was such a listing."
  [db blobs id f]
  (some? (deleting! db blobs (fn [tx]
                               (when-let [listing (jdbc/execute-one! tx ["SELECT title, user_id FROM listing WHERE id = ?" id]
                                                                         db/opts)]
                                 (f tx listing)
                                 (delete-in-tx! tx id))))))

(def ^:private card-select
  "Columns of a board card, with the cover photo; `l` is the listing."
  "SELECT l.id, l.user_id, l.title, l.kind, l.price_minor, l.category, l.status,
          l.pickup_place, l.pickup_city, l.created_at,
          p.object_key, p.width, p.height ")

(def ^:private cover-join
  "LEFT JOIN photo p ON p.listing_id = l.id AND p.position = 0 ")

(defn- parse-cursor
  [cursor]
  (when-let [[_ created-at id] (and (string? cursor) (re-matches #"(\d{1,15})\.(\d{1,18})" cursor))]
    [(parse-long created-at) (parse-long id)]))

(defn- cursor
  [{:keys [created-at id]}]
  (str created-at "." id))

(defn- card
  [{:keys [object-key width height] :as row}]
  (assoc (select-keys row [:id :user-id :title :kind :price-minor :category :pickup-place :pickup-city :status :created-at])
         :cover (when object-key {:object-key object-key :width width :height height})))

(def ^:private board-sql
  ;; The cursor bounds the index range: a missing one is the largest value,
  ;; rather than an OR, which would keep SQLite from seeking.
  (str card-select "FROM listing l " cover-join
       "WHERE l.status IN " on-board-sql "
          AND (?1 IS NULL OR l.kind = ?1)
          AND (?2 IS NULL OR l.category = ?2)
          AND (l.created_at, l.id) < (coalesce(?3, 9223372036854775807), coalesce(?4, 9223372036854775807))
        ORDER BY l.created_at DESC, l.id DESC
        LIMIT ?5"))

(defn board
  "Listings on the board, newest first, optionally of one `kind` and `category`.
  Returns `{:listings [...] :next cursor}`; pass `:next` back as `:before`
  for the following page (nil when there is none). Unreadable cursors start
  from the top. Ties on creation time are ordered by id, so pages never
  skip or repeat a listing."
  [db {:keys [kind category before limit]}]
  (let [[before-at before-id] (parse-cursor before)
        rows (jdbc/execute! db [board-sql kind category before-at before-id (inc limit)] db/opts)
        listings (mapv card (take limit rows))]
    {:listings listings
     :next (when (> (count rows) limit) (cursor (peek listings)))}))

(def ^:private max-search-terms 8)

(defn fts-query
  "FTS5 query for what a user typed, or nil when nothing is searchable. Only
  runs of letters and digits are kept, each quoted, so FTS5 operators and
  syntax in the input are plain text; the last term matches as a prefix,
  for results while still typing. Terms must all match."
  [s]
  (when (string? s)
    (let [terms (->> (re-seq #"[\p{L}\p{N}]+" s)
                     (map str/lower-case)
                     (take max-search-terms))]
      (when (seq terms)
        (str/join " " (concat (map #(str "\"" % "\"") (butlast terms))
                              [(str "\"" (last terms) "\"*")]))))))

(def ^:private max-search-page
  "Ranked results page by offset; deep pages cost more and are never useful."
  50)

(defn search
  "Listings on the board matching `q`, best first (title matches weigh ten times
  description matches), optionally of one `kind` and `category`. Returns
  `{:listings [...] :next page}` with :next nil on the last page."
  [db {:keys [q kind category page limit]}]
  (if-let [match (fts-query q)]
    (let [page (max 1 (min (or page 1) max-search-page))
          rows (jdbc/execute! db [(str card-select
                                       "FROM listing_fts JOIN listing l ON l.id = listing_fts.rowid "
                                       cover-join
                                       "WHERE listing_fts MATCH ?1
                                     AND l.status IN " on-board-sql "
                                     AND (?2 IS NULL OR l.kind = ?2)
                                     AND (?3 IS NULL OR l.category = ?3)
                                   ORDER BY bm25(listing_fts, 10.0, 1.0), l.created_at DESC, l.id DESC
                                   LIMIT ?4 OFFSET ?5")
                                  match kind category (inc limit) (* (dec page) limit)]
                              db/opts)]
      {:listings (mapv card (take limit rows))
       :next (when (and (> (count rows) limit) (< page max-search-page)) (inc page))})
    {:listings [] :next nil}))

(defn by-owner
  "All listings of `user-id`, any status, newest first."
  [db user-id]
  (mapv card (jdbc/execute! db [(str card-select "FROM listing l " cover-join
                                     "WHERE l.user_id = ? ORDER BY l.created_at DESC, l.id DESC")
                                user-id]
                            db/opts)))

(defn favorite!
  "Adds listing `id` to the favorites of `user-id`, unless it is theirs or
  their account is being deleted. True when it is among them afterwards."
  [db user-id id now]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["INSERT INTO favorite (user_id, listing_id, created_at, seen_status)
                            SELECT ?1, id, ?2, status FROM listing WHERE id = ?3 AND user_id <> ?1
                              AND EXISTS (SELECT 1 FROM user WHERE id = ?1 AND deleted_at IS NULL)
                            ON CONFLICT DO NOTHING"
                           user-id now id])
    (some? (jdbc/execute-one! tx ["SELECT 1 FROM favorite WHERE user_id = ? AND listing_id = ?" user-id id]))))

(defn unfavorite!
  [db user-id id]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx ["DELETE FROM favorite WHERE user_id = ? AND listing_id = ?" user-id id])))

(defn favorite-ids
  "Which of listings `ids` are among the favorites of `user-id`, as a set."
  [db user-id ids]
  (if (empty? ids)
    #{}
    (into #{}
          (map :listing-id)
          (jdbc/execute! db (into [(str "SELECT listing_id FROM favorite WHERE user_id = ? AND listing_id IN ("
                                        (str/join ", " (repeat (count ids) "?")) ")")
                                   user-id]
                                  ids)
                         db/opts))))

(defn favorites
  "The favorites of `user-id`, any status, most recently favorited first,
  `:changed` when their status is not the one last seen (see
  [[mark-favorites-seen!]])."
  [db user-id]
  (mapv (fn [row] (assoc (card row) :changed (not= (:status row) (:seen-status row))))
        (jdbc/execute! db [(str card-select ", f.seen_status FROM favorite f JOIN listing l ON l.id = f.listing_id "
                                cover-join
                                "WHERE f.user_id = ? ORDER BY f.created_at DESC, l.id DESC")
                           user-id]
                       db/opts)))

(defn changed-favorites
  "How many favorites of `user-id` changed status since last seen."
  [db user-id]
  (:n (jdbc/execute-one! db ["SELECT count(*) AS n FROM favorite f JOIN listing l ON l.id = f.listing_id
                              WHERE f.user_id = ? AND l.status <> f.seen_status"
                             user-id]
                         db/opts)))

(defn mark-favorites-seen!
  "Records the status each favorite of `user-id` has as seen: the one in
  `shown` (listings as displayed), so a change made since showing them
  stays news; the current one without."
  ([db user-id]
   (db/with-write-tx [tx db]
     (jdbc/execute-one! tx ["UPDATE favorite SET seen_status = (SELECT status FROM listing l WHERE l.id = favorite.listing_id)
                             WHERE user_id = ?"
                            user-id])))
  ([db user-id shown]
   (db/with-write-tx [tx db]
     (doseq [{:keys [id status]} shown]
       (jdbc/execute-one! tx ["UPDATE favorite SET seen_status = ? WHERE user_id = ? AND listing_id = ?"
                              status user-id id])))))

(defn update!
  "Edits listing `id` on the board, owned by `user-id`: new field values, removing
  the photos whose ids are in `remove-ids` and adding the prepared `photos`
  after the kept ones, which come in `order` (photo ids, the cover first;
  those not in it after, as they were). Returns `{:ok true}`, `{:errors
  ...}` when the photos would exceed the limit, or nil when there is no
  such listing to edit. New photos are uploaded first and deleted again if
  the edit is not made; removed photos' objects are deleted once it is."
  [db blobs id user-id {:keys [kind title description price-minor category pickup-place pickup-city]}
   remove-ids photos now & [{:keys [order]}]]
  (let [removing (set remove-ids)
        current (by-id db id)]
    (if (> (+ (count (remove (comp removing :id) (:photos current))) (count photos)) max-photos)
      ;; Checked before uploading anything; the transaction checks again.
      (too-many-photos)
      (let [result (with-uploads
                     db blobs photos now
                     (fn [object-keys]
                       (db/with-write-tx [tx db]
                       ;; Photos as of this transaction, not the read above.
                         (let [rows (photo-rows tx id)
                               rank (zipmap order (range))
                               kept (sort-by #(rank (:id %) (count rank)) (remove (comp removing :id) rows))
                               removed (filter (comp removing :id) rows)]
                           ;; Both checked before anything is written: a refused edit changes nothing.
                           (cond
                             (not (jdbc/execute-one! tx [(str "SELECT 1 FROM listing
                                                               WHERE id = ? AND user_id = ? AND status IN " on-board-sql)
                                                         id user-id]))
                             {:not-found true :uploaded object-keys}

                             (> (+ (count kept) (count photos)) max-photos)
                             (assoc (too-many-photos) :uploaded object-keys)

                             :else
                             ;; A wanted post is not reserved (see [[move-allowed?]]).
                             (do (jdbc/execute-one! tx ["UPDATE listing
                                                         SET kind = ?1, title = ?2, description = ?3, price_minor = ?4,
                                                             category = ?5, pickup_place = ?6, pickup_city = ?7,
                                                             status = CASE WHEN ?1 = 'wanted' THEN 'active' ELSE status END,
                                                             updated_at = ?8, fresh_at = ?8, reminded_at = NULL, reminder_give_ups = 0
                                                         WHERE id = ?9"
                                                        kind title description price-minor category
                                                        pickup-place pickup-city now id])
                                 (doseq [{:keys [id]} removed]
                                   (jdbc/execute-one! tx ["DELETE FROM photo WHERE id = ?" id]))
                               ;; Positions are unique and few: the kept photos are put back in
                               ;; their order, each under its own id.
                                 (doseq [{:keys [id]} kept]
                                   (jdbc/execute-one! tx ["DELETE FROM photo WHERE id = ?" id]))
                                 (doseq [[position {photo-id :id :keys [object-key width height]}] (map-indexed vector kept)]
                                   (jdbc/execute-one! tx ["INSERT INTO photo (id, listing_id, position, object_key, width, height)
                                                           VALUES (?, ?, ?, ?, ?, ?)"
                                                          photo-id id position object-key width height]))
                                 (doseq [[position object-key {:keys [full]}]
                                         (map vector (iterate inc (count kept)) object-keys photos)]
                                   (jdbc/execute-one! tx ["INSERT INTO photo (listing_id, position, object_key, width, height)
                                                         VALUES (?, ?, ?, ?, ?)"
                                                          id position object-key (:width full) (:height full)]))
                                 (unqueue-deletions! tx object-keys)
                                 (queue-deletions! tx (map :object-key removed) 0)
                                 {:ok true :removed (map :object-key removed)}))))))]
        (if (:ok result)
          (do (delete-objects! db blobs (:removed result))
              {:ok true})
          (do (delete-objects! db blobs (:uploaded result))
              (when-not (:not-found result) (dissoc result :uploaded))))))))

(defn facets
  "Counts of listings on the board for its filters: per kind within the
  chosen `category`, per category within the chosen `kind`, and in total
  within both; all within the search `q` when there is one."
  [db {:keys [q kind category]}]
  (let [match (fts-query q)
        rows (jdbc/execute! db (if match
                                 [(str "SELECT l.kind, l.category, count(*) AS n
                                        FROM listing_fts JOIN listing l ON l.id = listing_fts.rowid
                                        WHERE listing_fts MATCH ? AND l.status IN " on-board-sql "
                                        GROUP BY l.kind, l.category")
                                  match]
                                 [(str "SELECT kind, category, count(*) AS n FROM listing
                                        WHERE status IN " on-board-sql " GROUP BY kind, category")])
                            db/opts)
        sum-by (fn [k pred] (reduce (fn [acc row] (cond-> acc (pred row) (update (k row) (fnil + 0) (:n row))))
                                    {} rows))]
    {:kinds (sum-by :kind #(or (nil? category) (= category (:category %))))
     :categories (sum-by :category #(or (nil? kind) (= kind (:kind %))))
     :total (reduce + 0 (map :n (filter #(and (or (nil? kind) (= kind (:kind %)))
                                              (or (nil? category) (= category (:category %))))
                                        rows)))}))
