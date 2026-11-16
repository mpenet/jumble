(ns s-exp.jumble.listing.choices
  "The categories and pickup places listings choose from, managed by
  moderators. Ids are stored on listings and never change; labels can be
  renamed. Retired entries are no longer offered; their listings keep their
  label."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.web.params :as params])
  (:import (java.text Normalizer Normalizer$Form)))

(set! *warn-on-reflection* true)

(def ^:private tables
  "Which list, to its table and the listing column referencing it."
  {:category {:table "category" :column "category"}
   :place {:table "place" :column "pickup_place"}})

(def max-label 60)

(defn- entries
  [db which]
  (jdbc/execute! db
                 [(str "SELECT id, label, retired_at FROM "
                       (get-in tables [which :table])
                       " ORDER BY position, id")]
                 db/opts))

(defn all
  "`{:categories [...] :places [...]}`, each entry `{:id :label :retired-at}`
  (nil unless retired), in their order."
  [db]
  {:categories (entries db :category)
   :places (entries db :place)})

(defn- default-categories
  "The categories a new Jumble starts with (migration 009), by id: their
  English label and its translation."
  []
  [["electronics" "Electronics" (i18n/t "Electronics")]
   ["home" "Furniture & home" (i18n/t "Furniture & home")]
   ["kids" "Kids & baby" (i18n/t "Kids & baby")]
   ["sports" "Sports & outdoors" (i18n/t "Sports & outdoors")]
   ["books-media" "Books & media" (i18n/t "Books & media")]
   ["clothing" "Clothing" (i18n/t "Clothing")]
   ["tickets" "Tickets & vouchers" (i18n/t "Tickets & vouchers")]
   ["other" "Other" (i18n/t "Other")]])

(defn localize-defaults!
  "Names the default categories still carrying their English label in the
  language in use; those moderators renamed are left alone, and so is a
  translation another category already bears."
  [db]
  (db/with-write-tx [tx db]
    (doseq [[id english translated] (default-categories)
            :when (not= english translated)]
      (jdbc/execute-one! tx
                         ["UPDATE category SET label = ?1 WHERE id = ?2 AND label = ?3
                              AND NOT EXISTS (SELECT 1 FROM category WHERE label = ?1 COLLATE NOCASE AND id <> ?2)"
                          translated
                          id
                          english]))))

(defn choosable
  "The `entries` not retired: those offered to listings."
  [entries]
  (filterv (comp nil? :retired-at) entries))

(defn listing-counts
  "Number of listings per entry id of `which` list."
  [db which]
  (let [{:keys [column]} (tables which)]
    (into {}
          (map (juxt :id :n))
          (jdbc/execute! db
                         [(str "SELECT "
                               column
                               " AS id, count(*) AS n FROM listing
                                   WHERE "
                               column
                               " IS NOT NULL GROUP BY "
                               column)]
                         db/opts))))

(defn slug
  "Lowercase ASCII letters, digits and dashes from `label`; accents dropped,
  anything else turned into a dash. Empty when nothing is left."
  [label]
  (-> (Normalizer/normalize (str/lower-case (str/trim label)) Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")
      (as-> s (subs s 0 (min 40 (count s))))
      (str/replace #"-+$" "")))

(defn- label-errors
  "Errors in `label` for the entry `id` of `which` list (nil for a new one)."
  [tx which id label]
  (cond
    (str/blank? label) {:label (i18n/t "Give it a name.")}
    (> (count label) max-label) {:label (i18n/t "Keep the name to {max} characters or fewer." {:max max-label})}

    :else
    (when-let [{other :label} (jdbc/execute-one! tx
                                                 [(str "SELECT label FROM "
                                                       (get-in tables [which :table])
                                                       " WHERE label = ? COLLATE NOCASE AND id IS NOT ?")
                                                  label
                                                  id]
                                                 db/opts)]
      {:label (i18n/t "There is already one named “{label}”." {:label other})})))

(defn- free-id
  "`base`, or `base-2`, `base-3`… the first not taken in `table`."
  [tx table base]
  (let [taken (into #{}
                    (map :id)
                    (jdbc/execute! tx
                                   [(str "SELECT id FROM " table " WHERE id = ? OR id LIKE ?")
                                    base
                                    (str base "-%")]
                                   db/opts))]
    (first (remove taken (cons base (map #(str base "-" %) (iterate inc 2)))))))

(defn add!
  "Adds an entry to `which` list (:category or :place), last. Returns
  `{:id id}` or `{:errors {:label message}}`."
  [db which label]
  (let [label (params/trimmed label)
        {:keys [table]} (tables which)]
    (db/with-write-tx [tx db]
      (or (some->> (label-errors tx which nil label) (hash-map :errors))
          (let [id (free-id tx table (or (not-empty (slug label)) table))]
            (jdbc/execute-one! tx
                               [(str "INSERT INTO "
                                     table
                                     " (id, label, position)
                                         SELECT ?, ?, coalesce(max(position), 0) + 1 FROM "
                                     table)
                                id
                                label])
            {:id id})))))

(defn rename!
  "Renames entry `id`. Returns `{:ok true}`, `{:errors {:label message}}`,
  or nil when there is no such entry."
  [db which id label]
  (let [label (params/trimmed label)]
    (db/with-write-tx [tx db]
      (or (some->> (label-errors tx which id label) (hash-map :errors))
          (when (pos? (::jdbc/update-count (jdbc/execute-one! tx
                                                              [(str "UPDATE "
                                                                    (get-in tables [which :table])
                                                                    " SET label = ? WHERE id = ?")
                                                               label
                                                               id])))
            {:ok true})))))

(defn move!
  "Swaps entry `id` with the next choosable one before it (`:up`) or after
  it (`:down`); at either end, nothing changes."
  [db which id direction]
  (let [{:keys [table]} (tables which)]
    (db/with-write-tx [tx db]
      (let [ordered (jdbc/execute! tx
                                   [(str "SELECT id, position FROM "
                                         table
                                         " WHERE retired_at IS NULL ORDER BY position, id")]
                                   db/opts)
            ordered (cond-> ordered (= :up direction) reverse)
            [entry neighbour] (->> ordered (drop-while #(not= id (:id %))) (take 2))]
        (when neighbour
          (jdbc/execute-one! tx [(str "UPDATE " table " SET position = ? WHERE id = ?") (:position neighbour) id])
          (jdbc/execute-one! tx [(str "UPDATE " table " SET position = ? WHERE id = ?") (:position entry) (:id neighbour)]))))))

(defn retire!
  "Stops offering entry `id`. The last category stays: a listing needs
  one. Returns `{:ok true}`, `{:errors {:label message}}`, or nil when
  there is no such choosable entry."
  [db which id now]
  (let [{:keys [table]} (tables which)]
    (db/with-write-tx [tx db]
      (if (and (= :category which)
               (= [{:id id}] (jdbc/execute! tx ["SELECT id FROM category WHERE retired_at IS NULL"] db/opts)))
        {:errors {:label (i18n/t "Keep at least one category.")}}
        (when (pos? (::jdbc/update-count (jdbc/execute-one! tx
                                                            [(str "UPDATE "
                                                                  table
                                                                  " SET retired_at = ?
                                                                      WHERE id = ? AND retired_at IS NULL")
                                                             now
                                                             id])))
          {:ok true})))))

(defn restore!
  "Offers retired entry `id` again, where it was. Returns `{:ok true}`, or
  nil when there is no such retired entry."
  [db which id]
  (db/with-write-tx [tx db]
    (when (pos? (::jdbc/update-count (jdbc/execute-one! tx
                                                        [(str "UPDATE "
                                                              (get-in tables [which :table])
                                                              " SET retired_at = NULL
                                                                   WHERE id = ? AND retired_at IS NOT NULL")
                                                         id])))
      {:ok true})))
