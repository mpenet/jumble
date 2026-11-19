(ns seed
  "Fills the development database and storage with members and listings,
  through the same model functions the application uses. When alice (the
  Dex dev user) has signed in once, she gets conversations about two of them.

    clojure -M:dev:seed           add the seed data
    clojure -M:dev:seed --reset   remove every listing first"
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.account.model :as account]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.chat.model :as chat]
            [s-exp.jumble.config :as config]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.listing.choices :as choices]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.message.model :as message]
            [s-exp.jumble.store.migrate :as migrate])
  (:import (java.awt Color Font RenderingHints)
           (java.awt.image BufferedImage)
           (java.io ByteArrayOutputStream)
           (javax.imageio ImageIO)))

(set! *warn-on-reflection* true)

(def members
  [["Ana Ribeiro" "ana.ribeiro"] ["Benoît Favre" "benoit.favre"] ["Chloé Martin" "chloe.martin"]
   ["David Okafor" "david.okafor"] ["Elena Rossi" "elena.rossi"] ["Felix Wagner" "felix.wagner"]
   ["Grace Kim" "grace.kim"] ["Hugo Bernard" "hugo.bernard"]])

(def listings
  ;; kind, title, price, category, city (nil = first place), description
  [["sale" "IKEA Markus office chair" "60" "home" nil "Black mesh, adjustable armrests. Moving and can't take it."]
   ["free" "Box of children's books" nil "kids" nil "About 30 picture books, ages 3 to 6. Some well loved."]
   ["sale" "Road bike, 56 cm frame" "350" "sports" "Lausanne" "Aluminium frame, Shimano 105, new tyres in spring."]
   ["lend" "Pressure washer" nil "home" nil "Kärcher K4. Happy to lend for a weekend."]
   ["sale" "Nintendo Switch OLED" "220" "electronics" nil "White, two controllers, Mario Kart included."]
   ["sale" "Standing desk frame" "150" "home" "Geneva" "Electric, dual motor, top not included."]
   ["free" "Moving boxes (about 20)" nil "other" nil "Flat-packed, used once."]
   ["sale" "Ski boots, size 27.5" "80" "sports" nil "Salomon, worn one season."]
   ["sale" "Kindle Paperwhite" "70" "electronics" nil "11th gen, with cover. Battery still great."]
   ["lend" "Camping tent for 4" nil "sports" "Zurich" "Easy pitch, used a handful of times."]
   ["sale" "Baby stroller" "120" "kids" nil "Bugaboo Bee, folds small, rain cover included."]
   ["free" "Monstera cutting" nil "home" nil "Rooted in water, ready to pot."]
   ["sale" "Concert tickets x2" "90" "tickets" nil "Two seats next to each other, Saturday 8 pm."]
   ["sale" "Mechanical keyboard" "65" "electronics" nil "Tenkeyless, brown switches, USB-C."]
   ["sale" "Winter jacket, size M" "45" "clothing" nil "Patagonia down jacket, dark blue."]
   ["lend" "Drill and bit set" nil "home" nil "Bosch cordless, two batteries."]
   ["sale" "Board game bundle" "40" "books-media" nil "Catan, Ticket to Ride and Codenames."]
   ["free" "Kids' bike with stabilisers" nil "kids" "Lausanne" "16 inch wheels, red. Outgrown."]
   ["sale" "27\" 4K monitor" "180" "electronics" nil "Dell, USB-C with power delivery, stand included."]
   ["sale" "Espresso machine" "110" "home" nil "De'Longhi Dedica, descaled last month."]
   ["sale" "Hiking boots, size 42" "55" "sports" nil "Lowa, waterproof, resoled once."]
   ["free" "Programming books" nil "books-media" nil "SICP, Clean Code, and a few others. Take them all."]
   ["sale" "Gift voucher, 100" "85" "tickets" nil "Bookshop voucher valid until next year."]
   ["lend" "Projector" nil "electronics" nil "Full HD, good for movie nights. HDMI cable included."]
   ["sale" "Wool rug 160x230" "75" "home" "Geneva" "Beige, no stains, rolled for transport."]
   ["sale" "Running watch" "95" "sports" nil "Garmin Forerunner, charger included."]
   ["free" "Baby clothes 0-6 months" nil "kids" nil "A big bag, mostly neutral colours."]
   ["sale" "Rain jacket, size L" "30" "clothing" nil "Light and packable, barely worn."]
   ["sale" "Noise cancelling headphones" "130" "electronics" nil "Sony WH-1000XM4, with case."]
   ["lend" "Car roof box" nil "other" "Zurich" "Thule, 420 litres, fits most roof bars."]
   ["sale" "Bookshelf" "35" "home" nil "White, 5 shelves, easy to take apart."]
   ["free" "Vinyl records" nil "books-media" nil "Around 40 records, mostly 70s rock."]
   ["sale" "Climbing shoes, size 41" "40" "sports" nil "La Sportiva, plenty of rubber left."]
   ["sale" "Kids' car seat" "70" "kids" nil "Group 1/2/3, Isofix, never in an accident."]
   ["sale" "Theatre tickets" "50" "tickets" nil "Two tickets, Thursday evening, row F."]
   ["free" "Office plants" nil "home" nil "Three pothos plants that need a new desk."]
   ["sale" "Tablet 10\"" "160" "electronics" nil "Wi-Fi only, 128 GB, with keyboard case."]
   ["sale" "Wetsuit, size M" "60" "sports" "Lausanne" "4/3 mm, good for the lake."]
   ["lend" "Sewing machine" nil "home" nil "Singer, simple to use, with spare needles."]
   ["sale" "Leather messenger bag" "45" "clothing" nil "Brown, fits a 14 inch laptop."]])

(def palette
  {"electronics" 0x2f5d50 "home" 0x8a5a44 "kids" 0xc0773a "sports" 0x3b6ea5
   "books-media" 0x6b4c8a "clothing" 0x9b3d5a "tickets" 0x4f7d3a "other" 0x5c5c66})

(defn- placeholder
  "PNG bytes of a card showing `title` on the category's colour."
  [title category variant]
  (let [w 1200 h 900
        img (BufferedImage. w h BufferedImage/TYPE_INT_RGB)
        g (.createGraphics img)
        out (ByteArrayOutputStream.)]
    (try
      (.setRenderingHint g RenderingHints/KEY_TEXT_ANTIALIASING RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
      (let [base (Color. (int (palette category 0x5c5c66)))]
        (.setColor g (if (odd? variant) base (.brighter base))))
      (.fillRect g 0 0 w h)
      (.setColor g Color/WHITE)
      (.setFont g (Font. Font/SANS_SERIF Font/BOLD 64))
      (let [fm (.getFontMetrics g)
            x (max 40 (quot (- w (.stringWidth fm title)) 2))]
        (.drawString g ^String title (int x) (int (quot h 2))))
      (.setFont g (Font. Font/SANS_SERIF Font/PLAIN 36))
      (.drawString g (str "photo " (inc variant)) 40 (- h 50))
      (ImageIO/write img "png" out)
      (.toByteArray out)
      (finally
        (.dispose g)))))

(defn- remove-listings!
  [ds blobs]
  (let [keys (map :object_key
                  (jdbc/execute! ds
                                 ["SELECT object_key FROM photo"]
                                 {:builder-fn rs/as-unqualified-maps}))]
    (doseq [k keys size ["full" "thumb"]]
      (blob/delete! blobs (listing/photo-key k size)))
    (db/with-write-tx [tx ds]
      (jdbc/execute! tx ["DELETE FROM listing"]))
    (println "Removed" (count keys) "photos and all listings.")))

(def ^:private day (* 24 60 60 1000))

(def ^:private conversations
  [["Is it still available?" "Hi! Is it still available? I could pick it up tomorrow around noon."
    "Yes it is! Tomorrow 12:30 works for me, I'll be at the usual spot."]
   ["Pickup this week?" "Hello, I'm interested. Could I come by on Thursday?"
    "Thursday is fine, I'm around all day. Send me a message when you're on your way."]])

(defn- seed-conversations!
  "alice writes to the owners of the first `listing-ids`; each answers."
  [ds listing-ids now]
  (when-let [{alice :id} (jdbc/execute-one! ds
                                            ["SELECT id FROM user WHERE email = 'alice@example.com'"]
                                            {:builder-fn rs/as-unqualified-maps})]
    (doseq [[listing-id [subject question answer]] (map vector listing-ids conversations)
            :let [found (listing/by-id ds listing-id)
                  thread-id (message/start! ds
                                            {:sender-id alice :listing-id listing-id :subject subject
                                             :body question :now (- now (* 2 60 60 1000))})]]
      (message/reply! ds thread-id (:user-id found) answer (- now (* 60 60 1000))))
    (println "Seeded" (count conversations) "conversations for alice@example.com.")))

(defn -main
  [& args]
  (when-not (= "dev" (System/getProperty "jumble.profile"))
    (binding [*out* *err*]
      (println "Refusing to seed outside the dev profile (use clojure -M:dev:seed)."))
    (System/exit 1))
  (let [{:keys [db storage currency] :as cfg} (config/load-config)
        ds (db/datasource (:path db))
        blobs (blob/s3-store storage)
        now (System/currentTimeMillis)]
    (migrate/migrate! ds (migrate/migrations))
    (db/pin-settings! ds {"currency" currency})
    ;; The seeded members, and the Dex users at example.com.
    (account/allow! ds {:patterns ["*@example.com"] :moderator-id nil :now now})
    (when (some #{"--reset"} args)
      (remove-listings! ds blobs))
    (when (empty? (choices/choosable (:places (choices/all ds))))
      (choices/add! ds :place "Main pickup point"))
    (let [users (mapv (fn [[name handle]]
                        (account/upsert-user! ds
                                              {:iss "seed" :sub handle :name name
                                               :email (str handle "@example.com")}
                                              now))
                      members)
          settings (merge (select-keys cfg [:currency]) (choices/all ds))
          place (some-> (choices/choosable (:places settings)) first :id)
          listing-ids
          (vec (for [[i [kind title price category city description]] (map-indexed vector listings)
                     :let [{:keys [listing errors]}
                           (listing/validate settings
                                             {:kind kind :title title :description description :price price
                                              :category category
                                              :pickup (if (or city (nil? place)) "city" (listing/place-pickup place))
                                              :city (or city "Geneva")})
                           _ (when errors (throw (ex-info "Invalid seed listing" {:title title :errors errors})))
                           {:keys [photos]} (listing/prepare-photos (map #(placeholder title category %)
                                                                         (range (inc (mod i 3)))))]]
                 (let [id (listing/create! ds blobs (:id (nth users (mod i (count users)))) listing photos now)]
                   ;; Spread over the last three weeks, newest last.
                   (listing/backdate! ds id (- now (* (- (count listings) i) (quot (* 21 day) (count listings)))))
                   id)))]
      ;; Not news: a chat channel connected in development is not told.
      (chat/forget! ds listing-ids)
      (println "Seeded" (count users) "members and" (count listings) "listings.")
      (seed-conversations! ds listing-ids now))))
