(ns s-exp.jumble.listing.model-test
  "Listings: validation, photos and their storage, statuses, the board,
  search, favorites and edits."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture]]
            [s-exp.jumble.image :as image]
            [s-exp.jumble.listing.model :as listing]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(def settings fixtures/settings)

(def valid-input
  {:kind "sale" :title " Desk lamp " :description " Warm light.\n" :price "25"
   :category "home" :pickup "place:hall" :city ""})

(defn- errors [input] (:errors (listing/validate settings input)))

(deftest validate-test
  (testing "valid input is normalized"
    (is (= {:listing {:kind "sale" :title "Desk lamp" :description "Warm light." :price-minor 2500
                      :category "home" :pickup-place "hall" :pickup-city nil}}
           (listing/validate settings valid-input))))
  (testing "pickup in a city"
    (is (= {:pickup-place nil :pickup-city "Lausanne"}
           (select-keys (:listing (listing/validate settings (assoc valid-input :pickup "city" :city " Lausanne ")))
                        [:pickup-place :pickup-city]))))
  (testing "free and lend items carry no price, whatever was typed"
    (is (nil? (get-in (listing/validate settings (assoc valid-input :kind "free" :price "25"))
                      [:listing :price-minor]))))
  (testing "each problem is reported against its field"
    (is (= #{:kind} (set (keys (errors (assoc valid-input :kind "swap"))))))
    (is (= #{:title} (set (keys (errors (assoc valid-input :title "   "))))))
    (is (= #{:title} (set (keys (errors (assoc valid-input :title (apply str (repeat 121 "x"))))))))
    (is (= #{:description} (set (keys (errors (assoc valid-input :description (apply str (repeat 5001 "x"))))))))
    (is (= #{:price} (set (keys (errors (assoc valid-input :price ""))))))
    (is (= #{:price} (set (keys (errors (assoc valid-input :price "12.345"))))))
    (is (= #{:category} (set (keys (errors (assoc valid-input :category "weapons"))))))
    (is (= #{:pickup} (set (keys (errors (assoc valid-input :pickup "place:mars"))))))
    (is (= #{:pickup} (set (keys (errors (assoc valid-input :pickup "teleport"))))))
    (is (= #{:city} (set (keys (errors (assoc valid-input :pickup "city" :city " ")))))))
  (testing "several problems at once"
    (is (= #{:title :price :category} (set (keys (errors (assoc valid-input :title "" :price "x" :category "?")))))))
  (testing "missing values are errors too, not crashes (price only matters for sales)"
    (is (= #{:kind :title :category :pickup}
           (set (keys (errors {:kind nil :title nil :price nil :category nil :pickup nil})))))))

(defn- fixture-bytes
  ^bytes [name]
  (with-open [in (io/input-stream (io/resource (str "images/" name)))]
    (.readAllBytes in)))

(deftest prepare-photos-test
  (testing "images become stored versions, in upload order"
    (let [{:keys [photos errors]} (listing/prepare-photos [(fixture-bytes "rotated-gps.jpg")
                                                           (fixture-bytes "transparent.png")])]
      (is (nil? errors))
      (is (= [[20 40] [20 20]] (mapv (fn [p] [(get-in p [:full :width]) (get-in p [:full :height])]) photos)))))
  (testing "problems are reported, naming the file's position"
    (is (= {:photos "Photo 2 is not a JPEG, PNG, HEIC or AVIF image."}
           (:errors (listing/prepare-photos [(fixture-bytes "transparent.png") (fixture-bytes "tiny.gif")]))))
    (is (= {:photos "Photo 1 is too large (over 40 megapixels)."}
           (:errors (listing/prepare-photos [(fixture-bytes "huge.png")]))))
    (is (= {:photos "At most 5 photos."}
           (:errors (listing/prepare-photos (repeat 6 (fixture-bytes "transparent.png")))))))
  (testing "a photo taking too long to process is reported too, not a failure"
    (with-redefs [image/timeout-seconds 0]
      (is (= {:photos "Photo 1 took too long to process. Try a smaller one."}
             (:errors (listing/prepare-photos [(fixture-bytes "rotated-gps.jpg")]))))))
  (testing "and one that could not get its turn"
    (let [^java.util.concurrent.Semaphore slot @#'image/processing]
      (.acquire slot)
      (try
        (with-redefs [image/wait-seconds 0]
          (is (= {:photos "Too many photos are being processed right now. Please try again in a moment."}
                 (:errors (listing/prepare-photos [(fixture-bytes "rotated-gps.jpg")])))))
        (finally
          (.release slot))))))

(defn- user-id
  []
  (:id (jdbc/execute-one! *db* ["INSERT INTO user (iss, sub, email, name, created_at, last_login_at)
                                 VALUES ('i', 's', 'a@example.com', 'Alice', 0, 0) RETURNING id"]
                          {:builder-fn rs/as-unqualified-maps})))

(deftest create-test
  (let [blobs (blob/memory-store)
        owner (user-id)
        {:keys [photos]} (listing/prepare-photos [(fixture-bytes "rotated-gps.jpg")
                                                  (fixture-bytes "transparent.png")])
        input (:listing (listing/validate settings valid-input))
        id (listing/create! *db* blobs owner input photos 1000)
        stored (listing/by-id *db* id)]
    (testing "the listing reads back with its owner"
      (is (= (assoc input :id id :user-id owner :status "active" :created-at 1000 :updated-at 1000 :reminded-at nil
                    :owner-name "Alice" :owner-email "a@example.com")
             (dissoc stored :photos))))
    (testing "photos are stored in both sizes and listed in order"
      (is (= [0 1] (mapv :position (:photos stored))))
      (doseq [{:keys [object-key]} (:photos stored)
              size ["full" "thumb"]]
        (is (some? (blob/fetch blobs (listing/photo-key object-key size))))))
    (testing "unknown ids read as nil"
      (is (nil? (listing/by-id *db* 999))))))

(deftest create-cleans-up-on-failure-test
  (let [blobs (blob/memory-store)
        {:keys [photos]} (listing/prepare-photos [(fixture-bytes "transparent.png")])
        input (:listing (listing/validate settings valid-input))]
    (testing "when the database refuses the listing, uploaded photos are removed"
      (is (thrown? java.sql.SQLException (listing/create! *db* blobs (user-id) (assoc input :category "nope") photos 1000)))
      (is (empty? @(:objects blobs))))
    (testing "so they are when its owner is gone"
      (is (nil? (listing/create! *db* blobs 999 input photos 1000)))
      (is (empty? @(:objects blobs))))))

(defn- failing-store
  "A memory store whose puts and deletes of keys matching `fail?` throw."
  [{:keys [put-fails delete-fails]}]
  (let [store (blob/memory-store)]
    {:store store
     :failing (reify blob/Store
                (put! [_ key data content-type]
                  (when (and put-fails (re-find put-fails key)) (throw (ex-info "storage down" {})))
                  (blob/put! store key data content-type))
                (fetch [_ key] (blob/fetch store key))
                (delete! [_ key]
                  (when (and delete-fails (re-find delete-fails key)) (throw (ex-info "storage down" {})))
                  (blob/delete! store key))
                (url [_ key now] (blob/url store key now)))}))

(deftest storage-failure-cleanup-test
  (let [{:keys [photos]} (listing/prepare-photos [(fixture-bytes "transparent.png") (fixture-bytes "transparent.png")])
        input (:listing (listing/validate settings valid-input))
        owner (user-id)]
    (testing "a photo whose thumbnail fails to upload leaves no full size behind"
      (let [{:keys [store failing]} (failing-store {:put-fails #"thumb"})]
        (is (thrown? clojure.lang.ExceptionInfo (listing/create! *db* failing owner input photos 1000)))
        (is (empty? @(:objects store)))))
    (testing "one object failing to delete does not stop the others"
      (let [{:keys [store failing]} (failing-store {:delete-fails #"full"})
            id (listing/create! *db* failing owner input photos 1000)]
        (is (true? (listing/delete! *db* failing id owner)))
        (is (= 2 (count @(:objects store))) "only the two full sizes remain")
        (is (every? #(str/ends-with? % "full.jpg") (keys @(:objects store))))))))

(defn- post!
  "Creates a listing at time `at` from `valid-input` with `overrides`; returns its id."
  [owner at overrides & {:keys [photos]}]
  (listing/create! *db* (blob/memory-store) owner
                   (:listing (listing/validate settings (merge valid-input overrides)))
                   (or photos [])
                   at))

(deftest board-test
  (let [owner (user-id)
        lamp (post! owner 100 {:title "Lamp" :kind "sale" :price "10" :category "home"})
        books (post! owner 200 {:title "Books" :kind "free" :category "other"})
        drill (post! owner 300 {:title "Drill" :kind "lend" :category "home"})
        sold (post! owner 400 {:title "Sold chair" :kind "sale" :price "5" :category "home"})
        titles #(mapv :title (:listings %))]
    (jdbc/execute! *db* ["UPDATE listing SET status = 'sold' WHERE id = ?" sold])
    (testing "active listings, newest first"
      (is (= ["Drill" "Books" "Lamp"] (titles (listing/board *db* {:limit 10})))))
    (testing "filters by kind and by category, together"
      (is (= ["Books"] (titles (listing/board *db* {:kind "free" :limit 10}))))
      (is (= ["Drill" "Lamp"] (titles (listing/board *db* {:category "home" :limit 10}))))
      (is (= ["Lamp"] (titles (listing/board *db* {:kind "sale" :category "home" :limit 10})))))
    (testing "cards carry what the board shows"
      (is (= {:id lamp :user-id owner :title "Lamp" :kind "sale" :price-minor 1000 :category "home" :status "active"
              :pickup-place "hall" :pickup-city nil :created-at 100 :cover nil}
             (last (:listings (listing/board *db* {:limit 10}))))))
    (is (some? books))
    (is (some? drill))))

(deftest board-pagination-test
  (let [owner (user-id)
        ;; Same timestamp for several listings: the id breaks ties.
        ids (mapv #(post! owner (if (< % 3) 500 (* 100 %)) {:title (str "Item " %)}) (range 7))
        page (fn [cursor] (listing/board *db* {:limit 3 :before cursor}))
        p1 (page nil)
        p2 (page (:next p1))
        p3 (page (:next p2))]
    (testing "pages follow each other without gaps or repeats"
      ;; Items 0-6 at times 500 500 500 300 400 500 600: newest first, then
      ;; the higher id among equal times.
      (is (= (mapv ids [6 5 2 1 0 4 3])
             (mapv :id (concat (:listings p1) (:listings p2) (:listings p3))))))
    (testing "the last page says there is no more"
      (is (= [3 3 1] (mapv (comp count :listings) [p1 p2 p3])))
      (is (nil? (:next p3))))
    (testing "the cursor is an opaque string; garbage starts from the top"
      (is (string? (:next p1)))
      (is (= (map :id (:listings p1)) (map :id (:listings (page "nonsense"))))))))

(deftest board-cover-test
  (let [owner (user-id)
        {:keys [photos]} (listing/prepare-photos [(fixture-bytes "transparent.png") (fixture-bytes "rotated-gps.jpg")])
        id (post! owner 100 {} :photos photos)
        cover (:cover (first (:listings (listing/board *db* {:limit 10}))))]
    (testing "the first photo is the cover, with its size"
      (is (= (select-keys (first (:photos (listing/by-id *db* id))) [:object-key :width :height])
             cover)))))

(deftest fts-query-test
  (testing "terms are quoted; the last one matches as a prefix"
    (is (= "\"bike\"*" (listing/fts-query "bike")))
    (is (= "\"red\" \"bike\"*" (listing/fts-query "  red   bike ")))
    (is (= "\"vélo\"*" (listing/fts-query "Vélo"))))
  (testing "FTS5 syntax in the input is only ever text"
    (is (= "\"or\"*" (listing/fts-query "\" OR *")))
    (is (= "\"near\" \"a\" \"b\"*" (listing/fts-query "NEAR(a b)")))
    (is (= "\"title\" \"x\"*" (listing/fts-query "title:x")))
    (is (= "\"foo\" \"bar\"*" (listing/fts-query "-foo ^bar"))))
  (testing "nothing searchable"
    (is (nil? (listing/fts-query nil)))
    (is (nil? (listing/fts-query "")))
    (is (nil? (listing/fts-query "*** \"\" ()"))))
  (testing "at most 8 terms"
    (is (= 8 (count (re-seq #"\"[^\"]+\"" (listing/fts-query "a b c d e f g h i j")))))))

(deftest search-test
  (let [owner (user-id)
        bike (post! owner 100 {:title "Vélo de ville" :description "Bicycle, 3 speeds" :category "other"})
        lamp (post! owner 200 {:title "Desk lamp" :description "Goes well with a bike basket"})
        bell (post! owner 300 {:title "Bike bell" :kind "free"})
        gone (post! owner 400 {:title "Bike pump"})
        found #(mapv :id (:listings (listing/search *db* (merge {:limit 10 :page 1} %))))]
    (jdbc/execute! *db* ["UPDATE listing SET status = 'sold' WHERE id = ?" gone])
    (testing "prefix and accent-insensitive matching, active listings only"
      (is (= #{bike} (set (found {:q "velo"}))))
      (is (= #{bike} (set (found {:q "bicyc"}))))
      (is (not (some #{gone} (found {:q "bike"})))))
    (testing "title matches rank above description matches"
      (is (= [bell lamp] (found {:q "bike"}))))
    (testing "combines with filters"
      (is (= [bell] (found {:q "bike" :kind "free"})))
      (is (= [] (found {:q "bike" :category "other"}))))
    (testing "hostile input searches as text instead of failing"
      (doseq [q ["\"" "NEAR(" "a OR" "*" "title:" "AND AND" "(((" "^"]]
        (is (vector? (found {:q q})) q)))))

(deftest search-pagination-test
  (let [owner (user-id)]
    (doseq [i (range 5)]
      (post! owner (* 100 (inc i)) {:title (str "Chair " i)}))
    (let [page #(listing/search *db* {:q "chair" :limit 2 :page %})]
      (is (= [2 2 1] (mapv (comp count :listings page) [1 2 3])))
      (is (= [2 3 nil] (mapv (comp :next page) [1 2 3])))
      (is (= #{"Chair 0" "Chair 1" "Chair 2" "Chair 3" "Chair 4"}
             (set (mapcat (comp #(map :title %) :listings page) [1 2 3])))))))

(defn- other-user-id
  []
  (:id (jdbc/execute-one! *db* ["INSERT INTO user (iss, sub, email, name, created_at, last_login_at)
                                 VALUES ('i', 's2', 'b@example.com', 'Bob', 0, 0) RETURNING id"]
                          {:builder-fn rs/as-unqualified-maps})))

(defn- status-of [id] (:status (listing/by-id *db* id)))

(deftest set-status-test
  (let [owner (user-id)
        other (other-user-id)
        sale (post! owner 100 {:kind "sale" :price "10"})
        free (post! owner 100 {:kind "free"})
        lend (post! owner 100 {:kind "lend"})]
    (testing "sales are sold, free items given away, by their owner"
      (is (listing/set-status! *db* sale owner "sold" 200))
      (is (= "sold" (status-of sale)))
      (is (listing/set-status! *db* free owner "given" 200))
      (is (= "given" (status-of free))))
    (testing "and can be put back on the board"
      (is (listing/set-status! *db* sale owner "active" 300))
      (is (= ["active" 300] ((juxt :status :updated-at) (listing/by-id *db* sale)))))
    (testing "refused: other people's listings, wrong kind, unknown or same status"
      (is (not (listing/set-status! *db* sale other "sold" 400)))
      (is (not (listing/set-status! *db* free owner "sold" 400)))
      (is (not (listing/set-status! *db* lend owner "given" 400)))
      (is (not (listing/set-status! *db* sale owner "withdrawn" 400)))
      (is (not (listing/set-status! *db* sale owner "active" 400)))
      (is (= "active" (status-of sale))))))

(deftest wanted-test
  (let [owner (user-id)
        wanted (post! owner 100 {:kind "wanted" :price "30" :title "Child's bike"})]
    (testing "a post of what someone looks for: no price, whatever was typed"
      (is (= ["wanted" nil] ((juxt :kind :price-minor) (listing/by-id *db* wanted)))))
    (testing "found once someone had one, then off the board; back on it if need be"
      (is (= ["found"] (listing/status-moves (listing/by-id *db* wanted))))
      (is (listing/set-status! *db* wanted owner "found" 200))
      (is (= "found" (status-of wanted)))
      (is (= ["active"] (listing/status-moves (listing/by-id *db* wanted))))
      (is (listing/set-status! *db* wanted owner "active" 300)))
    (testing "neither sold, given away nor reserved; and nothing else is found"
      (is (not (listing/set-status! *db* wanted owner "sold" 400)))
      (is (not (listing/set-status! *db* wanted owner "given" 400)))
      (is (not (listing/set-status! *db* wanted owner "reserved" 400)))
      (is (not (listing/set-status! *db* (post! owner 100 {:kind "free"}) owner "found" 400))))
    (testing "on the board, counted with its kind"
      (is (= 1 (get-in (listing/facets *db* {}) [:kinds "wanted"]))))
    (testing "a reserved listing edited into a wanted post is available again"
      (let [bike (post! owner 100 {:kind "sale" :price "10" :title "Bike"})]
        (listing/set-status! *db* bike owner "reserved" 500)
        (is (:ok (listing/update! *db* (blob/memory-store) bike owner
                                  (:listing (listing/validate settings (assoc valid-input :kind "wanted")))
                                  #{} [] 600)))
        (is (= "active" (status-of bike)))))))

(deftest delete-test
  (let [blobs (blob/memory-store)
        owner (user-id)
        other (other-user-id)
        {:keys [photos]} (listing/prepare-photos [(fixture-bytes "transparent.png")])
        id (listing/create! *db* blobs owner (:listing (listing/validate settings valid-input)) photos 100)]
    (testing "only the owner can remove a listing"
      (is (not (listing/delete! *db* blobs id other)))
      (is (some? (listing/by-id *db* id))))
    (testing "removal deletes the listing, its photo rows and stored objects"
      (is (listing/delete! *db* blobs id owner))
      (is (nil? (listing/by-id *db* id)))
      (is (= 0 (:n (jdbc/execute-one! *db* ["SELECT count(*) AS n FROM photo"]))))
      (is (empty? @(:objects blobs))))
    (testing "removing again is a no"
      (is (not (listing/delete! *db* blobs id owner))))))

(deftest by-owner-test
  (let [owner (user-id)
        other (other-user-id)
        a (post! owner 100 {:title "A"})
        b (post! owner 200 {:title "B" :kind "sale" :price "5"})
        _ (post! other 300 {:title "Not mine"})]
    (listing/set-status! *db* b owner "sold" 250)
    (testing "all of the owner's listings, any status, newest first"
      (is (= [["B" "sold"] ["A" "active"]]
             (mapv (juxt :title :status) (listing/by-owner *db* owner)))))
    (is (some? a))))

(defn- photo-keys [id] (mapv :object-key (:photos (listing/by-id *db* id))))

(deftest update-test
  (let [blobs (blob/memory-store)
        owner (user-id)
        other (other-user-id)
        prepared #(:photos (listing/prepare-photos (map fixture-bytes %)))
        id (listing/create! *db* blobs owner (:listing (listing/validate settings valid-input))
                            (prepared ["transparent.png" "rotated-gps.jpg" "transparent.png"]) 100)
        [p0 p1 p2] (:photos (listing/by-id *db* id))
        changes (:listing (listing/validate settings (assoc valid-input :title "Floor lamp" :kind "free")))]
    (testing "fields change, removed photos go, new ones come after the kept ones"
      (is (= {:ok true} (listing/update! *db* blobs id owner changes [(:id p1)] (prepared ["rotated-gps.jpg"]) 200)))
      (let [updated (listing/by-id *db* id)]
        (is (= ["Floor lamp" "free" nil 200] ((juxt :title :kind :price-minor :updated-at) updated)))
        (is (= [0 1 2] (mapv :position (:photos updated))))
        (is (= [(:object-key p0) (:object-key p2)] (take 2 (photo-keys id))))
        (testing "the removed photo's objects are deleted, the others kept"
          (is (nil? (blob/fetch blobs (listing/photo-key (:object-key p1) "full"))))
          (doseq [k (photo-keys id)]
            (is (some? (blob/fetch blobs (listing/photo-key k "thumb"))))))))
    (testing "at most 5 photos once kept and new ones are counted"
      (let [before (photo-keys id)
            objects (count @(:objects blobs))]
        (is (= {:errors {:photos "At most 5 photos."}}
               (listing/update! *db* blobs id owner changes [] (prepared ["transparent.png" "transparent.png" "transparent.png"]) 300)))
        (is (= before (photo-keys id)))
        (is (= objects (count @(:objects blobs))) "nothing uploaded")))
    (testing "photos added meanwhile (another tab) refuse the edit whole, fields included"
      (let [before (listing/by-id *db* id)
            objects (count @(:objects blobs))]
        (with-redefs [listing/by-id (fn [_ _] (assoc before :photos []))]
          (is (= {:errors {:photos "At most 5 photos."}}
                 (listing/update! *db* blobs id owner (assoc changes :title "Desk lamp, again") []
                                  (prepared ["transparent.png" "transparent.png" "transparent.png"]) 350))))
        (is (= "Floor lamp" (:title (listing/by-id *db* id))))
        (is (= (:updated-at before) (:updated-at (listing/by-id *db* id))))
        (is (= objects (count @(:objects blobs))) "the photos uploaded for it deleted again")))
    (testing "only the owner, only active listings"
      (is (nil? (listing/update! *db* blobs id other changes [] [] 400)))
      (listing/set-status! *db* id owner "given" 400)
      (is (nil? (listing/update! *db* blobs id owner changes [] [] 500)))
      (is (= "Floor lamp" (:title (listing/by-id *db* id)))))))

(deftest update-ignores-foreign-photos-test
  (let [blobs (blob/memory-store)
        owner (user-id)
        prepared #(:photos (listing/prepare-photos (map fixture-bytes %)))
        mine (listing/create! *db* blobs owner (:listing (listing/validate settings valid-input)) (prepared ["transparent.png"]) 100)
        theirs (listing/create! *db* blobs owner (:listing (listing/validate settings valid-input)) (prepared ["transparent.png"]) 100)
        their-photo (:id (first (:photos (listing/by-id *db* theirs))))]
    (listing/update! *db* blobs mine owner (:listing (listing/validate settings valid-input)) [their-photo] [] 200)
    (is (= 1 (count (:photos (listing/by-id *db* theirs)))) "another listing's photo id is ignored")))

(deftest facets-test
  (let [owner (user-id)]
    (post! owner 100 {:title "Desk lamp" :kind "sale" :price "5" :category "home"})
    (post! owner 200 {:title "Floor lamp" :kind "free" :category "home"})
    (post! owner 300 {:title "Lamp shade" :kind "free" :category "other"})
    (post! owner 400 {:title "Drill" :kind "lend" :category "home"})
    (let [sold (post! owner 500 {:title "Sold lamp" :kind "sale" :price "5" :category "home"})]
      (listing/set-status! *db* sold owner "sold" 600))
    (testing "counts per kind and per category, active listings only"
      (is (= {:kinds {"sale" 1 "free" 2 "lend" 1} :categories {"home" 3 "other" 1} :total 4}
             (listing/facets *db* {}))))
    (testing "each axis is counted within the other axis' choice"
      (is (= {:kinds {"sale" 1 "free" 1 "lend" 1} :categories {"home" 1 "other" 1} :total 1}
             (listing/facets *db* {:kind "free" :category "home"}))))
    (testing "and within the search"
      (is (= {:kinds {"sale" 1 "free" 2} :categories {"home" 2 "other" 1} :total 3}
             (listing/facets *db* {:q "lamp"}))))))

(defn- failing-deletes
  "A store that keeps objects in `inner` but cannot delete them."
  [inner]
  (reify blob/Store
    (put! [_ k data content-type] (blob/put! inner k data content-type))
    (fetch [_ k] (blob/fetch inner k))
    (delete! [_ _] (throw (java.io.IOException. "storage unavailable")))
    (url [_ k now] (blob/url inner k now))))

(deftest update-survives-failed-cleanup-test
  (let [inner (blob/memory-store)
        owner (user-id)
        prepared #(:photos (listing/prepare-photos (map fixture-bytes %)))
        id (listing/create! *db* inner owner (:listing (listing/validate settings valid-input))
                            (prepared ["transparent.png"]) 100)
        old (:id (first (:photos (listing/by-id *db* id))))]
    (testing "removed photos that cannot be deleted from storage do not undo or fail the edit"
      (is (= {:ok true} (listing/update! *db* (failing-deletes inner) id owner
                                         (:listing (listing/validate settings valid-input))
                                         [old] (prepared ["rotated-gps.jpg"]) 200)))
      (let [[photo :as photos] (:photos (listing/by-id *db* id))]
        (is (= 1 (count photos)))
        (testing "the new photo stays in storage"
          (is (some? (blob/fetch inner (listing/photo-key (:object-key photo) "full")))))))))

(deftest stale-edit-form-test
  (let [blobs (blob/memory-store)
        owner (user-id)
        prepared #(:photos (listing/prepare-photos (map fixture-bytes %)))
        id (listing/create! *db* blobs owner (:listing (listing/validate settings valid-input))
                            (prepared ["transparent.png" "rotated-gps.jpg"]) 100)
        [a b] (:photos (listing/by-id *db* id))
        unchanged (:listing (listing/validate settings valid-input))]
    (listing/update! *db* blobs id owner unchanged [(:id a)] [] 200)
    (testing "a form still showing the removed photo cannot remove another one by its old id"
      (listing/update! *db* blobs id owner unchanged [(:id a)] [] 300)
      (is (= [(:object-key b)] (mapv :object-key (:photos (listing/by-id *db* id))))))
    (testing "kept photos keep their ids and close up their positions"
      (is (= [[(:id b) 0]] (mapv (juxt :id :position) (:photos (listing/by-id *db* id))))))))

(deftest board-seeks-test
  (testing "a page deep in the board is found by the index, not by scanning from the top"
    (let [plan (mapv :detail (jdbc/execute! *db* (into [(str "EXPLAIN QUERY PLAN " @#'listing/board-sql)]
                                                       [nil nil 1000 5 25])
                                            {:builder-fn rs/as-unqualified-maps}))]
      (is (some #(re-find #"USING INDEX listing_board \(created_at<\?\)" %) plan) (pr-str plan))
      (is (not-any? #(re-find #"TEMP B-TREE" %) plan) "no sort: the index gives the order"))))

(deftest favorites-test
  (let [owner (user-id)
        fan (other-user-id)
        lamp (post! owner 100 {:title "Lamp"})
        desk (post! owner 200 {:title "Desk"})
        chair (post! owner 300 {:title "Chair"})]
    (testing "others' listings can be favorited, once"
      (is (listing/favorite! *db* fan lamp 1000))
      (is (listing/favorite! *db* fan desk 2000))
      (is (listing/favorite! *db* fan lamp 3000) "again: already one")
      (is (= #{lamp desk} (listing/favorite-ids *db* fan [lamp desk chair]))))
    (testing "not one's own, nor what does not exist"
      (is (not (listing/favorite! *db* owner chair 1000)))
      (is (not (listing/favorite! *db* fan 999999 1000)))
      (is (= #{} (listing/favorite-ids *db* owner [lamp desk chair]))))
    (testing "the favorites, most recently favorited first, sold ones included"
      (listing/set-status! *db* lamp owner "sold" 4000)
      (is (= [["Desk" "active"] ["Lamp" "sold"]] (mapv (juxt :title :status) (listing/favorites *db* fan)))))
    (testing "unfavoriting"
      (listing/unfavorite! *db* fan desk)
      (is (= ["Lamp"] (mapv :title (listing/favorites *db* fan)))))
    (testing "a removed listing leaves the favorites"
      (listing/delete! *db* (blob/memory-store) lamp owner)
      (is (= [] (listing/favorites *db* fan))))
    (testing "cards know their owner"
      (is (= owner (:user-id (first (listing/by-owner *db* owner))))))))

(deftest reserved-test
  (let [owner (user-id)
        other (other-user-id)
        sale (post! owner 100 {:kind "sale" :price "10" :title "Bike"})
        free (post! owner 200 {:kind "free" :title "Chair"})
        lend (post! owner 300 {:kind "lend" :title "Drill"})]
    (testing "any listing on the board can be reserved by its owner"
      (is (= #{"reserved" "sold"} (set (listing/status-moves (listing/by-id *db* sale)))))
      (is (listing/set-status! *db* sale owner "reserved" 400))
      (is (listing/set-status! *db* free owner "reserved" 400))
      (is (listing/set-status! *db* lend owner "reserved" 400))
      (is (not (listing/set-status! *db* sale other "active" 400))))
    (testing "reserved listings stay on the board, in search and in the counts"
      (is (= #{"Bike" "Chair" "Drill"} (set (map :title (:listings (listing/board *db* {:limit 10}))))))
      (is (= ["Bike"] (map :title (:listings (listing/search *db* {:q "bike" :page 1 :limit 10})))))
      (is (= 3 (:total (listing/facets *db* {})))))
    (testing "and can be edited"
      (is (:ok (listing/update! *db* (blob/memory-store) sale owner
                                (:listing (listing/validate settings (assoc valid-input :title "Red bike")))
                                #{} [] 500))))
    (testing "from reserved: back to available, or sold, or given away"
      (is (= #{"active" "sold"} (set (listing/status-moves (listing/by-id *db* sale)))))
      (is (= #{"active" "given"} (set (listing/status-moves (listing/by-id *db* free)))))
      (is (= #{"active"} (set (listing/status-moves (listing/by-id *db* lend)))))
      (is (listing/set-status! *db* free owner "given" 600))
      (is (listing/set-status! *db* lend owner "active" 600))
      (is (= "active" (status-of lend))))
    (testing "listings off the board can only be put back"
      (is (= ["active"] (listing/status-moves (listing/by-id *db* free))))
      (is (not (listing/set-status! *db* free owner "reserved" 700))))))

(deftest changed-favorites-test
  (let [owner (user-id)
        fan (other-user-id)
        lamp (post! owner 100 {:title "Lamp"})
        desk (post! owner 200 {:title "Desk"})]
    (listing/favorite! *db* fan lamp 300)
    (listing/favorite! *db* fan desk 300)
    (testing "nothing changed yet"
      (is (= 0 (listing/changed-favorites *db* fan))))
    (testing "a status change since the favorites were last seen counts"
      (listing/set-status! *db* lamp owner "reserved" 400)
      (is (= 1 (listing/changed-favorites *db* fan)))
      (is (= {"Lamp" true "Desk" false} (into {} (map (juxt :title :changed)) (listing/favorites *db* fan)))))
    (testing "seeing them resets it"
      (listing/mark-favorites-seen! *db* fan)
      (is (= 0 (listing/changed-favorites *db* fan)))
      (is (not-any? :changed (listing/favorites *db* fan))))
    (testing "and back again counts again"
      (listing/set-status! *db* lamp owner "active" 500)
      (is (= 1 (listing/changed-favorites *db* fan))))))

(deftest seen-as-shown-test
  (let [owner (user-id)
        fan (other-user-id)
        lamp (post! owner 100 {:title "Lamp"})]
    (listing/favorite! *db* fan lamp 300)
    (listing/set-status! *db* lamp owner "reserved" 400)
    (testing "a change made after the favorites were shown stays news"
      (let [shown (listing/favorites *db* fan)]
        (listing/set-status! *db* lamp owner "active" 500)
        (listing/mark-favorites-seen! *db* fan shown)
        (is (= 1 (listing/changed-favorites *db* fan)))))))

(defn- queued-deletions
  []
  (:n (jdbc/execute-one! *db* ["SELECT count(*) AS n FROM object_deletion"] {:builder-fn rs/as-unqualified-maps})))

(deftest object-deletion-queue-test
  (let [owner (user-id)
        prepared #(:photos (listing/prepare-photos (map fixture-bytes %)))
        input (:listing (listing/validate settings valid-input))
        hour (* 60 60 1000)]
    (testing "objects storage could not delete are deleted later, until done"
      (let [inner (blob/memory-store)
            id (listing/create! *db* inner owner input (prepared ["transparent.png"]) 100)]
        (is (true? (listing/delete! *db* (failing-deletes inner) id owner)))
        (is (= 2 (count @(:objects inner))) "still there")
        (listing/delete-due-objects! *db* (failing-deletes inner) 200)
        (is (= 2 (count @(:objects inner))) "storage still failing")
        (listing/delete-due-objects! *db* inner (+ 200 hour))
        (is (empty? @(:objects inner)))
        (is (zero? (queued-deletions)))))
    (testing "so are photos an edit removed"
      (let [inner (blob/memory-store)
            id (listing/create! *db* inner owner input (prepared ["transparent.png"]) 100)
            old (:id (first (:photos (listing/by-id *db* id))))]
        (listing/update! *db* (failing-deletes inner) id owner input [old] [] 200)
        (is (= 2 (count @(:objects inner))))
        (listing/delete-due-objects! *db* inner (+ 200 hour))
        (is (empty? @(:objects inner)))))
    (testing "uploads whose listing was never recorded, the process dying first, are deleted an hour later"
      (let [inner (blob/memory-store)]
        (with-redefs [listing/delete-objects-quietly! (fn [& _])]
          (is (thrown? java.sql.SQLException
                       (listing/create! *db* inner owner (assoc input :category "nope") (prepared ["transparent.png"]) 1000))))
        (is (= 2 (count @(:objects inner))))
        (listing/delete-due-objects! *db* inner (+ 1000 (dec hour)))
        (is (= 2 (count @(:objects inner))) "not before the hour")
        (listing/delete-due-objects! *db* inner (+ 1000 hour))
        (is (empty? @(:objects inner)))))
    (testing "a recorded listing's photos are never queued"
      (let [inner (blob/memory-store)]
        (listing/create! *db* inner owner input (prepared ["transparent.png"]) 1000)
        (is (zero? (queued-deletions)))
        (listing/delete-due-objects! *db* inner (+ 1000 (* 2 hour)))
        (is (= 2 (count @(:objects inner))))))))

(deftest photo-order-test
  (let [blobs (blob/memory-store)
        owner (user-id)
        prepared #(:photos (listing/prepare-photos (repeat % (fixture-bytes "transparent.png"))))
        fields (:listing (listing/validate settings valid-input))
        id (listing/create! *db* blobs owner fields (prepared 3) 100)
        ids #(mapv :id (:photos (listing/by-id *db* id)))
        [a b c] (ids)]
    (testing "photos put in the order given, each the same photo as before"
      (is (= {:ok true} (listing/update! *db* blobs id owner fields [] [] 200 {:order [c a b]})))
      (is (= [c a b] (ids))))
    (testing "those left out of the order keep theirs, after; removed ones go; new ones come last"
      (listing/update! *db* blobs id owner fields [b] (prepared 1) 300 {:order [a]})
      (let [[first second third] (ids)]
        (is (= [a c] [first second]))
        (is (not (#{a b c} third)))))
    (testing "ids that are not the listing's photos are ignored"
      (listing/update! *db* blobs id owner fields [] [] 400 {:order [999 c]})
      (is (= c (first (ids)))))))

(defn- hold-turn!
  "Starts an upload of member `user-id`'s that keeps its turn; returns a
  function ending it, which returns what the upload did: :done, or :busy
  without a turn."
  [user-id]
  (let [started (promise)
        release (promise)
        upload (future (listing/with-upload-turn user-id
                         #(do (deliver started true) @release :done)
                         #(do (deliver started true) :busy)))]
    @started
    #(do (deliver release true) @upload)))

(defn- upload! [user-id]
  (listing/with-upload-turn user-id (constantly :done) (constantly :busy)))

(deftest upload-turn-test
  (testing "a member's uploads one at a time, others not held back"
    (let [end (hold-turn! 1)]
      (is (= :busy (upload! 1)))
      (is (= :done (upload! 2)))
      (is (= :done (end)))
      (is (= :done (upload! 1)) "and again once done")))
  (testing "a few at once over everyone, each held in memory while it is taken in"
    (let [ends (mapv hold-turn! (range 10 (+ 10 listing/max-uploads)))]
      (is (= :busy (upload! 99)))
      (is (every? #{:done} (map #(%) ends)))
      (is (= :done (upload! 99)))))
  (testing "the turn is given back when the upload fails"
    (is (thrown? IllegalStateException (listing/with-upload-turn 1 #(throw (IllegalStateException.)) (constantly :busy))))
    (is (= :done (upload! 1)))))
