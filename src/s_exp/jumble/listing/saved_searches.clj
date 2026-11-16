(ns s-exp.jumble.listing.saved-searches
  "Searches members keep: a query, a type, a category, or any of them
  together. Once a day, each member with saved searches is emailed the
  listings posted since the last time that match them, if any; not their
  own, and only those still on the board."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.listing.choices :as choices]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.email.mail :as mail]
            [s-exp.jumble.email.outbox :as outbox]))

(set! *warn-on-reflection* true)

(def ^:private day (* 24 60 60 1000))

(def max-per-member 10)

(def ^:private max-per-search
  "Listings per search in one email; the rest are on the board."
  10)

(defn by-user
  "The saved searches of `user-id`, oldest first."
  [db user-id]
  (jdbc/execute! db
                 ["SELECT id, q, kind, category, created_at FROM saved_search WHERE user_id = ? ORDER BY id" user-id]
                 db/opts))

(defn save!
  "Saves the search `{:q :kind :category}` (valid filters, nil when unset)
  for `user-id`. Returns `{:id id}`, the existing one's when already saved,
  or `{:error :empty}` (nothing to search for) or `{:error :too-many}`."
  [db user-id {:keys [q kind category]} now]
  (if-not (or q kind category)
    {:error :empty}
    (db/with-write-tx [tx db]
      (or (some->> (jdbc/execute-one! tx
                                      ["SELECT id FROM saved_search
                                           WHERE user_id = ? AND q IS ? AND kind IS ? AND category IS ?"
                                       user-id
                                       q
                                       kind
                                       category]
                                      db/opts)
                   :id
                   (hash-map :id))
          (if (>= (:n (jdbc/execute-one! tx ["SELECT count(*) AS n FROM saved_search WHERE user_id = ?" user-id] db/opts))
                  max-per-member)
            {:error :too-many}
            (do
              ;; Only what is posted from now on is new for them, unless
              ;; their other searches are already being followed.
              (jdbc/execute-one! tx
                                 ["UPDATE user SET searches_checked_at =
                                        CASE WHEN EXISTS (SELECT 1 FROM saved_search WHERE user_id = ?1)
                                             THEN coalesce(searches_checked_at, ?2) ELSE ?2 END
                                      WHERE id = ?1"
                                  user-id
                                  now])
              {:id (:id (jdbc/execute-one! tx
                                           ["INSERT INTO saved_search (user_id, q, kind, category, created_at)
                                                VALUES (?, ?, ?, ?, ?) RETURNING id"
                                            user-id
                                            q
                                            kind
                                            category
                                            now]
                                           db/opts))}))))))

(defn remove!
  "Removes saved search `id` of `user-id`. True when there was one."
  [db user-id id]
  (db/with-write-tx [tx db]
    (some? (jdbc/execute-one! tx ["DELETE FROM saved_search WHERE id = ? AND user_id = ? RETURNING id" id user-id]))))

(defn describe
  "A saved search in words: “bike”, free, in Furniture & home."
  [{:keys [q kind category]} categories]
  (str/join ", "
            (remove nil?
                    [(when q (i18n/t "“{query}”" {:query q}))
                     (case kind
                       "sale" (i18n/t "for sale")
                       "free" (i18n/t "free")
                       "lend" (i18n/t "to lend")
                       "wanted" (i18n/t "wanted")
                       nil)
                     (when category
                       (i18n/t "in {category}"
                               {:category (or (some #(when (= category (:id %)) (:label %)) categories) category)}))])))

(defn- matches
  "Listings on the board, not `user-id`'s, posted in (`after`, `until`],
  matching `search`, oldest first."
  [db user-id {:keys [q kind category]} after until]
  (let [match (listing/fts-query q)]
    (jdbc/execute! db
                   (cond-> [(str "SELECT l.id, l.title FROM listing l "
                                 (when match "JOIN listing_fts ON listing_fts.rowid = l.id AND listing_fts MATCH ?6 ")
                                 "WHERE l.status IN "
                                 listing/on-board-sql
                                 "
                               AND l.user_id <> ?1 AND l.created_at > ?2 AND l.created_at <= ?3
                               AND (?4 IS NULL OR l.kind = ?4) AND (?5 IS NULL OR l.category = ?5)
                             ORDER BY l.created_at, l.id LIMIT "
                                 max-per-search)
                            user-id
                            after
                            until
                            kind
                            category]
                     match (conj match))
                   db/opts)))

(defn digest
  "The email to `email` about `found`: `[search listings]` pairs, each
  with something new."
  [{:keys [base-url app-name]} email categories found]
  (let [n (count (distinct (mapcat (comp (partial map :id) second) found)))]
    {:to email
     :subject (i18n/tn "New on {app-name}: {n} listing for your saved searches"
                       "New on {app-name}: {n} listings for your saved searches"
                       n
                       {:app-name app-name})
     :text (str (str/join "\n\n"
                          (for [[search listings] found]
                            (str (describe search categories)
                                 "\n"
                                 (str/join "\n"
                                           (for [{:keys [id title]} listings]
                                             (str "- " (mail/one-line title) ": " base-url "/listings/" id))))))
                "\n\n-- \n"
                (i18n/t "You get this email once a day at most, when something matches your saved searches.")
                "\n"
                (i18n/t "Manage them: {url}" {:url (str base-url "/my/searches")})
                "\n")}))

(defn send-due!
  "Emails, through the outbox, each member whose saved searches were last
  checked a day ago or more what is new for them, then marks them checked;
  someone no longer allowed in is only marked checked.
  The window ends a few minutes back: a listing being saved now, with an
  earlier creation time, is in the next one rather than in none. Runs every
  delivery round; writes only for members due."
  [db settings now]
  (when-let [due (seq (jdbc/execute! db
                                     ["SELECT u.id, u.email, u.language, u.searches_checked_at FROM user u
                                          WHERE u.searches_checked_at <= ? AND u.deleted_at IS NULL
                                            AND EXISTS (SELECT 1 FROM saved_search s WHERE s.user_id = u.id)
                                          LIMIT 50"
                                      (- now day)]
                                     db/opts))]
    (let [categories (:categories (choices/all db))
          allowed (account/allowed db (:allowed-emails settings))
          until (- now listing/settle-time)]
      (outbox/enqueue-each!
       due
       (fn [{:keys [id email language searches-checked-at]}]
         ;; Read in full before the write transaction.
         (let [found (when (allowed email)
                       (into []
                             (comp (map (fn [search] [search (matches db id search searches-checked-at until)]))
                                   (filter (comp seq second)))
                             (by-user db id)))]
           (db/with-write-tx [tx db]
             ;; Unless their account's deletion started meanwhile.
             (when (and (seq found)
                        (jdbc/execute-one! tx ["SELECT 1 FROM user WHERE id = ? AND deleted_at IS NULL" id]))
               (outbox/enqueue! tx (i18n/speaking language (digest settings email categories found)) now))
             (jdbc/execute-one! tx ["UPDATE user SET searches_checked_at = ? WHERE id = ?" until id]))))
       #(select-keys % [:id])))))
