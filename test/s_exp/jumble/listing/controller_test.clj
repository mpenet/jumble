(ns s-exp.jumble.listing.controller-test
  "Listings on the web: posting and editing with photos, the board and
  search, statuses, favorites and saved searches."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture sign-in!]]
            [s-exp.jumble.listing.choices :as choices]
            [s-exp.jumble.listing.model :as listing])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(def ^:dynamic *blobs* nil)

(use-fixtures :each
  db-fixture
  (fn [t] (binding [*blobs* (blob/memory-store)] (t))))

(defn- fixture-bytes
  ^bytes [name]
  (with-open [in (io/input-stream (io/resource (str "images/" name)))]
    (.readAllBytes in)))

(defn- multipart
  "Body and content type of a multipart/form-data request with `fields`
  (name -> string) and `files` ([name filename bytes])."
  [fields files]
  (let [boundary "jumble-test-boundary"
        out (ByteArrayOutputStream.)
        write (fn [^String s] (.write out (.getBytes s StandardCharsets/UTF_8)))]
    (doseq [[k v] fields]
      (write (str "--" boundary "\r\nContent-Disposition: form-data; name=\"" k "\"\r\n\r\n" v "\r\n")))
    (doseq [[k filename ^bytes bs] files]
      (write (str "--" boundary "\r\nContent-Disposition: form-data; name=\"" k "\"; filename=\"" filename "\"\r\n"
                  "Content-Type: application/octet-stream\r\n\r\n"))
      (.write out bs)
      (write "\r\n"))
    (write (str "--" boundary "--\r\n"))
    {:body (.toByteArray out)
     :content-type (str "multipart/form-data; boundary=" boundary)}))

(defn- request
  [method uri token & {:keys [fields files origin headers]}]
  (let [{:keys [^bytes body content-type]} (when (= :post method) (multipart fields files))]
    ((fixtures/handler {:blobs *blobs*})
     (cond-> {:request-method method
              :uri (first (str/split uri #"\?" 2))
              :query-string (second (str/split uri #"\?" 2))
              :headers (cond-> (merge {"origin" (or origin "http://localhost:8080")} headers)
                         token (assoc "cookie" (str "jumble_session=" token))
                         content-type (assoc "content-type" content-type
                                             "content-length" (str (alength body))))}
       body (assoc :body (ByteArrayInputStream. body))))))

(defn- post-free-listings!
  "`n` free listings named `<prefix> 0`.. in category home, one second apart."
  [user-id prefix n]
  (doseq [i (range n)]
    (listing/create! *db* *blobs* user-id
                     (:listing (listing/validate fixtures/settings
                                                 {:kind "free" :title (str prefix " " i) :category "home"
                                                  :pickup "place:hall"}))
                     [] (* 1000 (inc i)))))

(def valid-fields
  {"kind" "sale" "title" "Desk lamp" "description" "Warm light." "price" "25"
   "category" "home" "pickup" "place:hall" "city" ""})

(defn- listing-count []
  (:n (jdbc/execute-one! *db* ["SELECT count(*) AS n FROM listing"])))

(deftest new-form-test
  (testing "anonymous visitors are sent to sign in"
    (is (= 302 (:status (request :get "/listings/new" nil)))))
  (let [{:keys [status body]} (request :get "/listings/new" (sign-in! "alice@example.com"))]
    (is (= 200 status))
    (is (str/includes? body "<form class=\"listing-form\" method=\"post\" action=\"/listings\" enctype=\"multipart/form-data\""))
    (is (str/includes? body "<option value=\"home\">Furniture &amp; home</option>"))
    (is (str/includes? body "value=\"place:hall\""))
    (is (re-find #"value=\"place:hall\"[^>]*>[^<]*Main hall" body))
    (is (re-find #"value=\"city\"[^>]*>[^<]*Elsewhere" body))
    (is (str/includes? body "accept=\"image/jpeg,image/png,image/heic,image/heif,image/avif\""))
    (testing "Datastar signals hold only known values"
      (is (str/includes? body "data-signals=\"{&quot;kind&quot;:&quot;sale&quot;,&quot;pickup&quot;:&quot;place:hall&quot;}\"")))))

(deftest create-test
  (let [token (sign-in! "alice@example.com")
        created (fixtures/counted "jumble_listings_created_total")
        response (request :post "/listings" token
                          :fields valid-fields
                          :files [["photos" "lamp.jpg" (fixture-bytes "rotated-gps.jpg")]
                                  ["photos" "" (byte-array 0)]])
        id (some->> (get-in response [:headers "location"]) (re-find #"^/listings/(\d+)$") second parse-long)]
    (testing "redirects to the new listing"
      (is (= 303 (:status response)))
      (is (some? id)))
    (testing "stored with its photo; empty file inputs are ignored"
      (let [stored (listing/by-id *db* id)]
        (is (= ["Desk lamp" 2500 "alice@example.com"] [(:title stored) (:price-minor stored) (:owner-email stored)]))
        (is (= 1 (count (:photos stored))))
        (is (some? (blob/fetch *blobs* (listing/photo-key (:object-key (first (:photos stored))) "full"))))))
    (testing "counted, for activity over time"
      (is (= 1.0 (- (fixtures/counted "jumble_listings_created_total") created))))))

(deftest invalid-input-test
  (let [token (sign-in! "alice@example.com")
        {:keys [status body]} (request :post "/listings" token
                                       :fields (assoc valid-fields "title" "<b>Lamp</b>" "price" "cheap")
                                       :files [["photos" "lamp.jpg" (fixture-bytes "rotated-gps.jpg")]])]
    (is (= 422 status))
    (is (str/includes? body "Enter a price of at most one million, such as 25 or 12.50."))
    (testing "typed values are kept, escaped"
      (is (str/includes? body "value=\"&lt;b&gt;Lamp&lt;/b&gt;\""))
      (is (str/includes? body "value=\"cheap\"")))
    (testing "files cannot be kept by the browser, so the form says so"
      (is (str/includes? body "select the photos again")))
    (is (= 0 (listing-count)))))

(deftest invalid-photo-test
  (let [{:keys [status body]} (request :post "/listings" (sign-in! "alice@example.com")
                                       :fields valid-fields
                                       :files [["photos" "a.gif" (fixture-bytes "tiny.gif")]])]
    (is (= 422 status))
    (is (str/includes? body "Photo 1 is not a JPEG, PNG, HEIC or AVIF image."))
    (is (= 0 (listing-count)))))

(deftest signals-reflect-only-known-values-test
  (let [{:keys [body]} (request :post "/listings" (sign-in! "alice@example.com")
                                :fields (assoc valid-fields "kind" "x'});alert(1);({" "pickup" "evil" "title" ""))]
    (is (str/includes? body "data-signals=\"{&quot;kind&quot;:&quot;sale&quot;,&quot;pickup&quot;:&quot;&quot;}\""))
    (is (not (str/includes? body "alert(1)")))))

(deftest cross-origin-post-test
  (is (= 403 (:status (request :post "/listings" (sign-in! "alice@example.com")
                               :fields valid-fields :origin "https://evil.example.com")))))

(defn- create!
  [token fields]
  (let [response (request :post "/listings" token :fields fields
                          :files [["photos" "lamp.jpg" (fixture-bytes "rotated-gps.jpg")]])]
    (parse-long (second (re-find #"/listings/(\d+)" (get-in response [:headers "location"]))))))

(deftest show-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        id (create! alice (assoc valid-fields "title" "<script>x</script> lamp"))
        {:keys [status body]} (request :get (str "/listings/" id) bob)]
    (is (= 200 status))
    (testing "escaped title, formatted price, labels from configuration"
      (is (str/includes? body "&lt;script&gt;x&lt;/script&gt; lamp"))
      (is (not (str/includes? body "<script>x</script>")))
      (is (str/includes? body "CHF 25"))
      (is (str/includes? body "Furniture &amp; home"))
      (is (str/includes? body "Main hall")))
    (testing "photos load from storage at their presigned URLs"
      (is (re-find #"<img src=\"https://blobs\.test/photos/[^/]+/full\.jpg\"" body)))
    (testing "unknown and malformed ids"
      (is (= 404 (:status (request :get "/listings/999" bob))))
      (is (= 404 (:status (request :get "/listings/abc" bob)))))))

(deftest upload-limits-test
  (let [token (sign-in! "alice@example.com")
        photo ["photos" "p.png" (fixture-bytes "transparent.png")]]
    (testing "one photo too many gets the form's message"
      (let [{:keys [status body]} (request :post "/listings" token :fields valid-fields :files (repeat 6 photo))]
        (is (= 422 status))
        (is (str/includes? body "At most 5 photos."))))
    (testing "a few more still get the form's message"
      (is (= 422 (:status (request :post "/listings" token :fields valid-fields :files (repeat 7 photo))))))
    (testing "beyond the parser's part limit, and for oversized files, the upload is refused"
      (is (= 413 (:status (request :post "/listings" token :fields valid-fields :files (repeat 25 photo)))))
      (is (= 413 (:status (request :post "/listings" token :fields valid-fields
                                   :files [["photos" "big.jpg" (byte-array (* 11 1024 1024))]])))))
    (is (= 0 (listing-count)))))

(deftest upload-turn-test
  (let [token (sign-in! "alice@example.com")
        alice (fixtures/user-id "alice@example.com")
        started (promise)
        release (promise)
        held (future (listing/with-upload-turn alice #(do (deliver started true) @release) (constantly nil)))
        read (atom false)
        {:keys [^bytes body content-type]} (multipart valid-fields [])
        response (do @started
                     ((fixtures/handler {:blobs *blobs*})
                      {:request-method :post
                       :uri "/listings"
                       :headers {"origin" "http://localhost:8080"
                                 "cookie" (str "jumble_session=" token)
                                 "content-type" content-type
                                 "content-length" (str (alength body))}
                       :body (proxy [java.io.InputStream] []
                               (read
                                 ([] (reset! read true) -1)
                                 ([_] (reset! read true) -1)
                                 ([_ _ _] (reset! read true) -1)))}))]
    (deliver release true)
    @held
    (testing "a member's upload while another of theirs is under way is turned away, its body unread"
      (is (= 503 (:status response)))
      (is (str/includes? (:body response) "Too many photos are being processed right now."))
      (is (not @read)))
    (testing "back to the form it came from"
      (is (str/includes? (:body response) "href=\"/listings/new\"")))
    (is (= 0 (listing-count)))))

(defn- card-titles
  "Listing titles on a board page, in order."
  [body]
  (mapv second (re-seq #"<h2 class=\"card-title\">([^<]*)</h2>" body)))

(deftest board-test
  (testing "anonymous visitors are sent to sign in"
    (let [response (request :get "/listings" nil)]
      (is (= 302 (:status response)))
      (is (= "/login?return_to=%2Flistings" (get-in response [:headers "location"])))))
  (let [token (sign-in! "alice@example.com")
        _ (create! token (assoc valid-fields "title" "Lamp <b>"))
        _ (create! token (assoc valid-fields "title" "Books" "kind" "free" "category" "other"))
        {:keys [status headers body]} (request :get "/listings" token)]
    (is (= 200 status))
    (testing "newest first, titles escaped"
      (is (= ["Books" "Lamp &lt;b&gt;"] (card-titles body))))
    (testing "cards link to the listing and show its cover photo"
      (is (re-find #"<a class=\"card\" href=\"/listings/\d+\">" body))
      (is (re-find #"<img src=\"https://blobs\.test/photos/[^/]+/thumb\.jpg\"" body)))
    (testing "invites posting"
      (is (str/includes? body "href=\"/listings/new\"")))
    (testing "the page carries the nonce its policy allows"
      (let [nonce (second (re-find #"'nonce-([^']+)'" (get headers "content-security-policy")))]
        (is (str/includes? body (str "data-nonce=\"" nonce "\"")))))))

(deftest board-filters-test
  (let [token (sign-in! "alice@example.com")]
    (create! token (assoc valid-fields "title" "Lamp"))
    (create! token (assoc valid-fields "title" "Books" "kind" "free" "category" "other"))
    (create! token (assoc valid-fields "title" "Drill" "kind" "lend"))
    (testing "by kind and category"
      (is (= ["Books"] (card-titles (:body (request :get "/listings?kind=free" token)))))
      (is (= ["Drill" "Lamp"] (card-titles (:body (request :get "/listings?category=home" token)))))
      (is (= ["Lamp"] (card-titles (:body (request :get "/listings?kind=sale&category=home" token))))))
    (testing "filter links keep the other filter and mark the current one"
      (let [body (:body (request :get "/listings?kind=free" token))]
        (is (re-find #"<a [^>]*href=\"/listings\?kind=free&amp;category=other\"[^>]*>(?:(?!</a>).)*Other" body))
        (is (re-find #"<a [^>]*href=\"/listings\?kind=free\"[^>]*aria-current=\"page\"[^>]*><span class=\"option-label\">All" body))))
    (testing "unknown filter values show everything rather than failing"
      (is (= ["Drill" "Books" "Lamp"] (card-titles (:body (request :get "/listings?kind=bogus&category=%3Cx%3E" token))))))
    (testing "nothing matching"
      (is (str/includes? (:body (request :get "/listings?kind=free&category=home" token)) "Nothing here yet.")))))

(deftest board-pagination-test
  (let [token (sign-in! "alice@example.com")
        user-id (fixtures/user-id "alice@example.com")]
    (post-free-listings! user-id "Item" 26)
    (let [body (:body (request :get "/listings?category=home" token))
          older (some-> (re-find #"href=\"(/listings\?[^\"]*before=[^\"]*)\"" body) second (str/replace "&amp;" "&"))]
      (is (= 24 (count (card-titles body))))
      (is (= "Item 25" (first (card-titles body))))
      (testing "the next page link keeps the filters"
        (is (str/includes? older "category=home")))
      (let [next-body (:body (request :get older token))]
        (is (= ["Item 1" "Item 0"] (card-titles next-body)))
        (is (not (re-find #"<a href=\"[^\"]*before=" next-body)))))))

(deftest search-test
  (let [token (sign-in! "alice@example.com")]
    (create! token (assoc valid-fields "title" "Desk lamp"))
    (create! token (assoc valid-fields "title" "Floor lamp" "kind" "free"))
    (create! token (assoc valid-fields "title" "Books"))
    (testing "results for the query only"
      (is (= #{"Desk lamp" "Floor lamp"} (set (card-titles (:body (request :get "/listings?q=lam" token)))))))
    (testing "combined with a filter"
      (is (= ["Floor lamp"] (card-titles (:body (request :get "/listings?q=lamp&kind=free" token))))))
    (let [body (:body (request :get "/listings?q=%3Cb%3Elamp&kind=free" token))]
      (testing "the search box keeps the query, escaped, and the active filters"
        (is (re-find #"<input [^>]*name=\"q\" value=\"&lt;b&gt;lamp\"" body))
        (is (str/includes? body "<input type=\"hidden\" name=\"kind\" value=\"free\">")))
      (testing "filter links keep the query"
        (is (str/includes? (:body (request :get "/listings?q=lamp&kind=free" token))
                           "href=\"/listings?q=lamp&amp;kind=free&amp;category=home\"")))
      (testing "a way back to everything"
        (is (re-find #"<a href=\"/listings\?kind=free\"[^>]*>Clear search</a>" body)))
      (testing "the results say what they are for, inside what live search replaces"
        (is (re-find #"<div id=\"results\"[^>]*><p class=\"sr-only\"[^>]*>[^<]*</p><h2 class=\"results-heading\">Results for “&lt;b&gt;lamp”</h2>" body))))
    (testing "a query with nothing searchable shows the board"
      (is (= 3 (count (card-titles (:body (request :get "/listings?q=%22%22%28%29" token)))))))))

(deftest search-pagination-test
  (let [token (sign-in! "alice@example.com")
        user-id (fixtures/user-id "alice@example.com")]
    (post-free-listings! user-id "Chair" 26)
    (let [body (:body (request :get "/listings?q=chair" token))]
      (is (= 24 (count (card-titles body))))
      (is (str/includes? body "href=\"/listings?q=chair&amp;page=2\""))
      (is (= 2 (count (card-titles (:body (request :get "/listings?q=chair&page=2" token)))))))
    (testing "malformed page numbers start at the first page"
      (is (= 24 (count (card-titles (:body (request :get "/listings?q=chair&page=abc" token)))))))))

(def datastar {"datastar-request" "true"})

(deftest datastar-results-fragment-test
  (let [token (sign-in! "alice@example.com")]
    (create! token (assoc valid-fields "title" "Desk lamp"))
    (create! token (assoc valid-fields "title" "Books" "kind" "free"))
    (let [{:keys [status headers body]} (request :get "/listings?q=lamp&kind=sale" token :headers datastar)]
      (is (= 200 status))
      (is (= "text/html; charset=utf-8" (get headers "content-type")))
      (testing "only the parts that change, morphed in by id: no page shell"
        (is (not (str/includes? body "<!DOCTYPE")))
        (is (.startsWith ^String body "<span id=\"search-filters\">"))
        (is (str/includes? body "<div id=\"results\""))
        (is (= ["Desk lamp"] (card-titles body))))
      (testing "the hidden filter inputs follow the applied filters"
        (is (str/includes? body "<input type=\"hidden\" name=\"kind\" value=\"sale\">"))))))

(deftest datastar-load-more-test
  (let [token (sign-in! "alice@example.com")
        user-id (fixtures/user-id "alice@example.com")]
    (post-free-listings! user-id "Item" 26)
    (let [page (:body (request :get "/listings" token))
          more (some-> (re-find #"<p id=\"more\"[^>]*><a href=\"([^\"]+)\"" page) second (str/replace "&amp;" "&"))
          {:keys [headers body]} (request :get more token :headers (assoc datastar "jumble-fragment" "more"))]
      (testing "the next cards and the following link replace the link"
        (is (= "#more" (get headers "datastar-selector")))
        (is (= "replace" (get headers "datastar-mode")))
        (is (= ["Item 1" "Item 0"] (card-titles body)))
        (is (not (str/includes? body "<div id=\"results\"")))
        (testing "on the last page the link is replaced by nothing more to load"
          (is (not (re-find #"<a href=\"/listings\?before=" body))))))))

(deftest datastar-attributes-test
  (let [body (:body (request :get "/listings" (sign-in! "alice@example.com")))]
    (testing "live search, filter navigation and history are wired"
      (is (str/includes? body "data-on:input__debounce.300ms=\"el.form.requestSubmit()\""))
      (is (re-find #"<form id=\"search\"[^>]*data-on:submit=\"[^\"]*@get\(" body))
      (is (re-find #"data-on:click__prevent=\"[^\"]*history\.pushState" body))
      (is (str/includes? body "data-on:popstate__window=")))
    (testing "search runs as you type; without JS, Enter submits the single-field form"
      (let [form (re-find #"<form id=\"search\".*?</form>" body)]
        (is (not (str/includes? form "<button")))
        (is (= 1 (count (re-seq #"<input (?![^>]*type=\"hidden\")" form))))))
    (testing "results announce when they are loading"
      (is (str/includes? body "data-attr:aria-busy=\"$_loading\"")))
    (testing "every board request aborts the one before, so a slow earlier
              response cannot overwrite newer results"
      (let [fetches (->> (re-seq #"data-on:(?:submit|click__prevent|popstate__window)=\"([^\"]*)\"" body)
                         (map second)
                         (filter #(str/includes? % "@get(")))]
        (is (seq fetches))
        (doseq [expression fetches]
          (is (str/includes? expression "requestCancellation: window.jumbleBoard") expression))))
    (testing "the result count is announced to screen readers"
      (is (re-find #"<p class=\"sr-only\" aria-live=\"polite\">Showing \d+ listings?</p>" body)))))

(defn- post-form
  "POST of a urlencoded form, as browsers and Datastar's contentType form send."
  [uri token fields & {:keys [headers]}]
  (let [body (.getBytes ^String (str/join "&" (map (fn [[k v]] (str k "=" v)) fields)) StandardCharsets/UTF_8)]
    ((fixtures/handler {:blobs *blobs*})
     {:request-method :post
      :uri uri
      :headers (merge {"origin" "http://localhost:8080"
                       "cookie" (str "jumble_session=" token)
                       "content-type" "application/x-www-form-urlencoded"}
                      headers)
      :body (ByteArrayInputStream. body)})))

(deftest wanted-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")]
    (testing "the form offers to look for something"
      (is (re-find #"<input [^>]*name=\"kind\" value=\"wanted\"[^>]*>[^<]*<span[^>]*>Look for it</span>|value=\"wanted\"[^>]*>\s*Look for it"
                   (:body (request :get "/listings/new" alice)))))
    (let [id (create! alice (assoc valid-fields "kind" "wanted" "title" "Child's bike" "price" "40"))
          page #(:body (request :get (str "/listings/" id) %))]
      (testing "posted without a price, whatever was typed"
        (is (= ["wanted" nil] ((juxt :kind :price-minor) (listing/by-id *db* id)))))
      (testing "shown as wanted; others answer it with a message"
        (is (str/includes? (page bob) "Wanted"))
        (is (str/includes? (page bob) "I have one"))
        (is (str/includes? (page bob) (str "href=\"/listings/" id "/message\"")))
        (is (re-find #"</svg>Where</dt>" (page bob)) "where it is, rather than where to pick it up")
        (is (re-find #"<span data-show=\"\$kind === &apos;wanted&apos;\">Where</span>"
                     (:body (request :get (str "/listings/" id "/edit") alice)))
            "in the form too, while it is a wanted post")
        (is (str/includes? (:body (request :get (str "/listings/" id "/message") bob)) "placeholder=\"Hi! I have one.\"")))
      (testing "its owner marks it found, which takes it off the board"
        (is (re-find #"<button[^>]*name=\"status\" value=\"found\"[^>]*>Mark as found</button>" (page alice)))
        (is (not (str/includes? (page alice) "value=\"reserved\"")) "nothing promised to anyone")
        (is (= ["Child's bike"] (card-titles (:body (request :get "/listings?kind=wanted" bob))))
            "on the board, its kind a filter")
        (is (str/includes? (:body (request :get "/listings" bob)) "lending and looking for."))
        (let [found (fixtures/counted "jumble_listings_closed_total{outcome=\"found\"}")]
          (post-form (str "/listings/" id "/status") alice {"status" "found"})
          (is (= 1.0 (- (fixtures/counted "jumble_listings_closed_total{outcome=\"found\"}") found)) "counted, as it ended"))
        (is (= "found" (:status (listing/by-id *db* id))))
        (is (= [] (card-titles (:body (request :get "/listings?kind=wanted" bob)))))
        (is (str/includes? (page bob) "Found"))))))

(deftest owner-actions-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        id (create! alice valid-fields)
        page #(:body (request :get (str "/listings/" id) %))]
    (testing "owners get the actions their listing allows; others do not"
      (is (re-find #"<button[^>]*name=\"status\" value=\"sold\"[^>]*>Mark as sold</button>" (page alice)))
      (is (str/includes? (page alice) (str "href=\"/listings/" id "/remove\"")))
      (is (not (str/includes? (page bob) "name=\"status\""))))
    (testing "without JS, the form posts and comes back to the listing"
      (let [response (post-form (str "/listings/" id "/status") alice {"status" "sold"})]
        (is (= 303 (:status response)))
        (is (= (str "/listings/" id) (get-in response [:headers "location"])))
        (is (= "sold" (:status (listing/by-id *db* id))))))
    (testing "sold listings leave the board but stay viewable by link, marked"
      (is (= [] (card-titles (:body (request :get "/listings" bob)))))
      (is (str/includes? (page bob) "<span class=\"status\">Sold</span>")))
    (testing "with Datastar, only the status section comes back"
      (let [{:keys [status body]} (post-form (str "/listings/" id "/status") alice {"status" "active"}
                                             :headers datastar)]
        (is (= 200 status))
        (is (.startsWith ^String body "<section id=\"listing-status\""))
        (is (str/includes? body "Mark as sold"))
        (is (= "active" (:status (listing/by-id *db* id))))))
    (testing "other people cannot change it"
      (is (= 404 (:status (post-form (str "/listings/" id "/status") bob {"status" "sold"}))))
      (is (= "active" (:status (listing/by-id *db* id)))))))

(deftest remove-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        id (create! alice valid-fields)]
    (testing "a confirmation page first, for the owner only"
      (let [{:keys [status body]} (request :get (str "/listings/" id "/remove") alice)]
        (is (= 200 status))
        (is (str/includes? body (str "<form method=\"post\" action=\"/listings/" id "/remove\""))))
      (is (= 404 (:status (request :get (str "/listings/" id "/remove") bob)))))
    (testing "others cannot remove it"
      (is (= 404 (:status (post-form (str "/listings/" id "/remove") bob {}))))
      (is (some? (listing/by-id *db* id))))
    (testing "removal deletes it and its photos, then shows the owner's listings"
      (let [deleted (fixtures/counted "jumble_listings_closed_total{outcome=\"deleted\"}")
            response (post-form (str "/listings/" id "/remove") alice {})]
        (is (= 1.0 (- (fixtures/counted "jumble_listings_closed_total{outcome=\"deleted\"}") deleted)))
        (is (= 303 (:status response)))
        (is (= "/my/listings" (get-in response [:headers "location"]))))
      (is (nil? (listing/by-id *db* id)))
      (is (empty? @(:objects *blobs*))))))

(deftest my-listings-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")]
    (create! alice (assoc valid-fields "title" "Lamp"))
    (let [sold (create! alice (assoc valid-fields "title" "Chair"))]
      (post-form (str "/listings/" sold "/status") alice {"status" "sold"}))
    (create! bob (assoc valid-fields "title" "Bob's bike"))
    (let [{:keys [status body]} (request :get "/my/listings" alice)]
      (is (= 200 status))
      (testing "only mine, sold ones too, marked"
        (is (= ["Chair" "Lamp"] (card-titles body)))
        (is (str/includes? body "<span class=\"status\">Sold</span>"))))
    (testing "an empty list invites posting"
      (is (str/includes? (:body (request :get "/my/listings" (sign-in! "carol@example.com"))) "href=\"/listings/new\"")))
    (testing "reachable from the header"
      (is (str/includes? (:body (request :get "/" alice)) "<span>My listings</span></a>")))))

(deftest edit-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        id (create! alice (assoc valid-fields "price" "12.50"))
        edit-path (str "/listings/" id "/edit")
        photo-id (:id (first (:photos (listing/by-id *db* id))))]
    (testing "owners find an edit link on their active listing"
      (is (str/includes? (:body (request :get (str "/listings/" id) alice)) (str "href=\"" edit-path "\""))))
    (testing "the form comes prefilled, existing photos removable"
      (let [{:keys [status body]} (request :get edit-path alice)]
        (is (= 200 status))
        (is (str/includes? body (str "action=\"/listings/" id "\"")))
        (is (str/includes? body "value=\"Desk lamp\""))
        (is (str/includes? body "value=\"12.50\""))
        (is (re-find #"<input type=\"radio\" name=\"pickup\" value=\"place:hall\" checked" body))
        (is (str/includes? body (str "name=\"remove\" value=\"" photo-id "\"")))))
    (testing "others cannot edit"
      (is (= 404 (:status (request :get edit-path bob))))
      (is (= 404 (:status (request :post (str "/listings/" id) bob :fields valid-fields)))))
    (testing "saving applies the changes and removes ticked photos"
      (let [response (request :post (str "/listings/" id) alice
                              :fields (assoc valid-fields "title" "Floor lamp" "remove" (str photo-id)))]
        (is (= 303 (:status response)))
        (is (= (str "/listings/" id) (get-in response [:headers "location"])))
        (is (= ["Floor lamp" []] ((juxt :title :photos) (listing/by-id *db* id))))
        (is (empty? @(:objects *blobs*)))))
    (testing "invalid input comes back on the edit form"
      (let [{:keys [status body]} (request :post (str "/listings/" id) alice :fields (assoc valid-fields "title" ""))]
        (is (= 422 status))
        (is (str/includes? body (str "action=\"/listings/" id "\"")))
        (is (str/includes? body "Give it a title."))))
    (testing "sold listings are put back on the board before editing"
      (post-form (str "/listings/" id "/status") alice {"status" "sold"})
      (is (= 409 (:status (request :get edit-path alice)))))))

(deftest markdown-description-test
  (let [alice (sign-in! "alice@example.com")
        id (create! alice (assoc valid-fields "description" "**Solid** oak\n\n<script>alert(1)</script>"))
        body (:body (request :get (str "/listings/" id) alice))]
    (is (str/includes? body "<strong>Solid</strong> oak"))
    (is (not (str/includes? body "<script>alert")))
    (testing "the form says markdown works"
      (is (str/includes? (:body (request :get "/listings/new" alice)) "Markdown")))))

(deftest gallery-test
  (let [alice (sign-in! "alice@example.com")
        response (request :post "/listings" alice :fields valid-fields
                          :files [["photos" "a.jpg" (fixture-bytes "rotated-gps.jpg")]
                                  ["photos" "b.png" (fixture-bytes "transparent.png")]])
        body (:body (request :get (get-in response [:headers "location"]) alice))
        mains (re-seq #"<a class=\"gallery-main\"[^>]*>" body)]
    (testing "one large photo shown at a time, the others hidden until picked"
      (is (= 2 (count mains)))
      (is (not (str/includes? (first mains) " hidden")))
      (is (str/includes? (second mains) " hidden")))
    (testing "thumbnails switch the photo with Datastar, and link to it without"
      (is (re-find #"<a class=\"gallery-thumb\" href=\"https://blobs\.test/photos/[^\"]+/full\.jpg\"[^>]*data-on:click__prevent=\"\$photo = 1\"" body)))))

(deftest filter-axes-test
  (let [token (sign-in! "alice@example.com")]
    (create! token (assoc valid-fields "title" "Lamp"))
    (create! token (assoc valid-fields "title" "Books" "kind" "free" "category" "other"))
    (create! token (assoc valid-fields "title" "Chair" "kind" "free"))
    (let [body (:body (request :get "/listings?kind=free" token))
          filters (re-find #"<nav class=\"filters\".*?</nav>" body)
          option (fn [href] (some->> (re-find (re-pattern (str "<a [^>]*href=\"" (java.util.regex.Pattern/quote href)
                                                               "\"[^>]*>(.*?)</a>"))
                                              filters)
                                     second))]
      (testing "two labelled groups: type and category"
        (is (re-find #"<div class=\"filter-row\" role=\"group\" aria-labelledby=\"filter-type\">" body))
        (is (re-find #"<div class=\"filter-row\" role=\"group\" aria-labelledby=\"filter-category\">" body)))
      (testing "types are counted within the chosen category (none here)"
        (is (str/includes? (option "/listings") "<span class=\"count\">3</span>"))
        (is (str/includes? (option "/listings?kind=sale") "<span class=\"count\">1</span>")))
      (testing "categories are counted within the chosen type"
        (is (str/includes? (option "/listings?kind=free&amp;category=home") "<span class=\"count\">1</span>"))
        (is (str/includes? (option "/listings?kind=free&amp;category=other") "<span class=\"count\">1</span>"))))
    (let [body (:body (request :get "/listings?category=other" token))]
      (testing "types with nothing in them are marked, still reachable"
        (is (re-find #"<a class=\"[^\"]*\bunavailable\b[^\"]*\" href=\"/listings\?kind=lend&amp;category=other\"" body))))
    (let [body (:body (request :get "/listings?kind=lend" token))]
      (testing "categories with nothing in them are left out, unless chosen"
        (is (not (str/includes? body "href=\"/listings?kind=lend&amp;category=home\"")))
        (is (str/includes? (:body (request :get "/listings?kind=lend&category=home" token))
                           "href=\"/listings?kind=lend\""))))
    (let [body (:body (request :get "/listings?category=home" token))]
      (testing "the chosen category's chip clears it"
        (is (re-find #"<a class=\"chip\" href=\"/listings\"[^>]*aria-current=\"page\"[^>]*>(?:(?!</a>).)*Furniture &amp; home(?:(?!</a>).)*class=\"clear-option\"" body))))))

(deftest photos-as-text-field-test
  (testing "a photos field sent as text instead of files is ignored"
    (let [response (request :post "/listings" (sign-in! "alice@example.com")
                            :fields (assoc valid-fields "photos" "not a file"))]
      (is (= 303 (:status response))))))

(deftest edit-upload-too-large-test
  (let [token (sign-in! "alice@example.com")
        id (create! token valid-fields)
        {:keys [status body]} (request :post (str "/listings/" id) token
                                       :fields valid-fields
                                       :files [["photos" "big.jpg" (byte-array (* 11 1024 1024))]])]
    (is (= 413 status))
    (testing "back to the form it came from"
      (is (str/includes? body (str "href=\"/listings/" id "/edit\""))))))

(deftest ui-details-test
  (let [alice (sign-in! "alice@example.com")
        id (do (post-free-listings! (fixtures/user-id "alice@example.com") "Lamp" 1)
               (:id (jdbc/execute-one! *db* ["SELECT id FROM listing"] {:builder-fn rs/as-unqualified-maps})))]
    (testing "a search with nothing to search for keeps what was typed, and says so"
      (let [body (:body (request :get "/listings?q=%21%21%21" alice))]
        (is (str/includes? body "value=\"!!!\""))
        (is (str/includes? body "Nothing to search for"))))
    (testing "messages fit the problem"
      (let [body (:body (request :post "/listings" alice :fields (assoc valid-fields "pickup" "city" "city" (apply str (repeat 81 "x")))))]
        (is (str/includes? body "Keep the city to 80 characters or fewer.")))
      (let [body (:body (request :post "/listings" alice :fields (assoc valid-fields "kind" "sale" "price" "2000000")))]
        (is (str/includes? body "at most one million"))))
    (testing "a listing off the board says why it cannot be edited"
      (jdbc/execute! *db* ["UPDATE listing SET status = 'given' WHERE id = ?" id])
      (let [{:keys [status body]} (request :get (str "/listings/" id "/edit") alice)]
        (is (= 409 status))
        (is (str/includes? body "Put it back on the board"))))))

(deftest photo-controls-test
  (let [alice (sign-in! "alice@example.com")
        response (request :post "/listings" alice :fields valid-fields
                          :files [["photos" "a.png" (fixture-bytes "transparent.png")]
                                  ["photos" "b.png" (fixture-bytes "transparent.png")]])
        id (parse-long (second (re-find #"/listings/(\d+)" (get-in response [:headers "location"]))))]
    (testing "each photo's removal box says which photo"
      (let [body (:body (request :get (str "/listings/" id "/edit") alice))]
        (is (str/includes? body "Remove photo 1"))
        (is (str/includes? body "Remove photo 2"))))
    (testing "the gallery says which photo is shown"
      (let [body (:body (request :get (str "/listings/" id) alice))]
        (is (re-find #"class=\"gallery-thumb\"[^>]*aria-current=\"true\"" body))))))

(deftest choices-from-database-test
  (let [token (sign-in! "alice@example.com")
        owner (fixtures/user-id "alice@example.com")]
    (post-free-listings! owner "Chair" 1)
    (choices/add! *db* :category "Books")
    (choices/add! *db* :place "Library")
    (testing "what moderators add is offered at once"
      (let [body (:body (request :get "/listings/new" token))]
        (is (str/includes? body "<option value=\"books\">Books</option>"))
        (is (str/includes? body "value=\"place:library\""))))
    (choices/retire! *db* :category "home" 1)
    (choices/retire! *db* :place "hall" 1)
    (testing "retired entries are no longer offered"
      (let [body (:body (request :get "/listings/new" token))]
        (is (not (str/includes? body "<option value=\"home\"")))
        (is (not (str/includes? body "value=\"place:hall\""))))
      (is (not (str/includes? (:body (request :get "/listings" token)) "category=home")))
      (let [{:keys [status body]} (request :post "/listings" token :fields valid-fields)]
        (is (= 422 status))
        (is (str/includes? body "Choose a category."))
        (is (str/includes? body "Choose where to pick it up."))))
    (testing "their listings keep their labels"
      (let [body (:body (request :get "/listings" token))]
        (is (str/includes? body "Furniture &amp; home"))
        (is (str/includes? body "Main hall"))))
    (testing "editing such a listing asks for a category and a pickup again"
      (let [id (:id (first (listing/by-owner *db* owner)))
            body (:body (request :get (str "/listings/" id "/edit") token))]
        (is (re-find #"<option value=\"\" selected disabled>Choose a category</option>" body))
        (is (not (re-find #"name=\"pickup\" value=\"[^\"]*\" checked" body)))))))

(deftest favorites-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        owner (fixtures/user-id "alice@example.com")
        _ (post-free-listings! owner "Chair" 1)
        id (:id (first (listing/by-owner *db* owner)))
        page (str "/listings/" id)
        action (str "action=\"/listings/" id "/favorite\"")
        favorite (fn [token on & {:as opts}]
                   (fixtures/request :post (str page "/favorite") token
                                     :form (merge {"favorite" (if on "on" "off")} (:form opts))
                                     :headers (:headers opts)))
        pressed (fn [body] (re-find #"aria-pressed=\"true\"" body))]
    (testing "no heart on one's own listings"
      (is (not (str/includes? (:body (request :get page alice)) action)))
      (is (not (str/includes? (:body (request :get "/listings" alice)) action)))
      (is (= 404 (:status (favorite alice true)))))
    (testing "others see it, not pressed, on the listing, the board and the home page"
      (doseq [path [page "/listings" "/"]]
        (let [body (:body (request :get path bob))]
          (is (str/includes? body action) path)
          (is (not (pressed body)) path))))
    (testing "favoriting without JavaScript goes back to the page it was on"
      (let [response (favorite bob true :form {"return_to" "/listings?kind=free"})]
        (is (= 303 (:status response)))
        (is (= "/listings?kind=free" (get-in response [:headers "location"])))))
    (testing "then it shows pressed"
      (doseq [path [page "/listings" "/"]]
        (is (pressed (:body (request :get path bob))) path)))
    (testing "the favorites page lists it"
      (let [body (:body (request :get "/my/favorites" bob))]
        (is (= ["Chair 0"] (card-titles body)))
        (is (str/includes? body "<a href=\"/my/favorites\" aria-current=\"page\"><svg class=\"icon icon-heart\""))))
    (testing "with Datastar, the button is replaced in place"
      (let [{:keys [status body]} (favorite bob false :form {"view" "card"} :headers {"datastar-request" "true"})]
        (is (= 200 status))
        (is (str/includes? body (str "id=\"card-favorite-" id "\"")))
        (is (not (pressed body))))
      (let [{:keys [body]} (favorite bob true :headers {"datastar-request" "true"})]
        (is (str/includes? body (str "id=\"favorite-" id "\"")))
        (is (pressed body))))
    (testing "only paths on this site to go back to"
      (is (= "/" (get-in (favorite bob true :form {"return_to" "https://evil.example/"}) [:headers "location"]))))
    (testing "unknown listings"
      (is (= 404 (:status (fixtures/request :post "/listings/999999/favorite" bob :form {"favorite" "on"})))))
    (testing "nothing yet"
      (is (str/includes? (:body (request :get "/my/favorites" alice)) "No favorites yet.")))))

(deftest many-categories-test
  (let [token (sign-in! "alice@example.com")
        owner (fixtures/user-id "alice@example.com")
        ids (mapv #(:id (choices/add! *db* :category (str "Group " %))) (range 1 13))]
    (doseq [[i id] (map-indexed vector ids)]
      (listing/create! *db* *blobs* owner
                       (:listing (listing/validate (merge fixtures/settings (choices/all *db*))
                                                   {:kind "free" :title (str "Thing " i) :category id
                                                    :pickup "place:hall"}))
                       [] (inc i)))
    (let [body (:body (request :get "/listings" token))
          [shown more] (str/split body #"<details class=\"more-categories\"" 2)]
      (testing "the first ten categories with listings show, the others behind More"
        (is (str/includes? shown "category=group-10\""))
        (is (not (str/includes? shown "category=group-11\"")))
        (is (re-find #"^>\s*<summary>More categories \(2\)</summary>" more))
        (is (str/includes? more "category=group-12\"")))
      (testing "closed, unless it holds the chosen category"
        (is (not (re-find #"<details class=\"more-categories\" open" body)))
        (is (re-find #"<details class=\"more-categories\" open"
                     (:body (request :get "/listings?category=group-12" token))))))))

(deftest reserved-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        id (create! alice valid-fields)
        page #(:body (request :get (str "/listings/" id) %))]
    (testing "owners can reserve a listing"
      (is (re-find #"<button[^>]*name=\"status\" value=\"reserved\"[^>]*>Mark as reserved</button>" (page alice)))
      (post-form (str "/listings/" id "/status") alice {"status" "reserved"})
      (is (= "reserved" (:status (listing/by-id *db* id)))))
    (testing "it stays on the board, marked, and can still be messaged"
      (let [board (:body (request :get "/listings" bob))]
        (is (= ["Desk lamp"] (card-titles board)))
        (is (str/includes? board "<span class=\"status status-reserved\">Reserved</span>")))
      (is (str/includes? (page bob) "<span class=\"status status-reserved\">Reserved</span>"))
      (is (str/includes? (page bob) (str "href=\"/listings/" id "/message\""))))
    (testing "its owner can make it available again, or edit it"
      (is (re-find #"<button[^>]*name=\"status\" value=\"active\"[^>]*>Make available again</button>" (page alice)))
      (is (str/includes? (page alice) (str "href=\"/listings/" id "/edit\""))))))

(deftest freshness-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        id (create! alice valid-fields)
        page #(:body (request :get (str "/listings/" id) %))]
    (testing "no question before the owner is asked"
      (is (not (str/includes? (page alice) "Still available?"))))
    (jdbc/execute! *db* ["UPDATE listing SET reminded_at = 1 WHERE id = ?" id])
    (testing "once asked, the owner sees the question on the listing; others do not"
      (is (str/includes? (page alice) "Still available?"))
      (is (str/includes? (page alice) (str "action=\"/listings/" id "/fresh\"")))
      (is (not (str/includes? (page bob) "Still available?"))))
    (testing "answering keeps it and comes back to the listing"
      (let [response (post-form (str "/listings/" id "/fresh") alice {})]
        (is (= 303 (:status response)))
        (is (= (str "/listings/" id) (get-in response [:headers "location"]))))
      (is (nil? (:reminded-at (listing/by-id *db* id))))
      (is (not (str/includes? (page alice) "Still available?"))))
    (testing "others cannot answer"
      (is (= 404 (:status (post-form (str "/listings/" id "/fresh") bob {})))))
    (testing "expired listings are marked, and their owner can put them back"
      (jdbc/execute! *db* ["UPDATE listing SET status = 'expired' WHERE id = ?" id])
      (is (str/includes? (page bob) "<span class=\"status\">Expired</span>"))
      (is (not (str/includes? (page bob) (str "href=\"/listings/" id "/message\""))))
      (is (re-find #"<button[^>]*name=\"status\" value=\"active\"[^>]*>Put back on the board</button>" (page alice))))))

(deftest saved-searches-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        save (fn [token form] (fixtures/request :post "/my/searches" token :form form))]
    (testing "a search or filter can be saved from the board; plain browsing cannot"
      (is (str/includes? (:body (request :get "/listings?q=bike&kind=free" alice)) "action=\"/my/searches\""))
      (is (not (str/includes? (:body (request :get "/listings" alice)) "action=\"/my/searches\"")))
      (is (str/includes? (:body (request :get "/listings" alice)) "href=\"/my/searches\"")))
    (testing "saving goes to the saved searches, described"
      (let [response (save alice {"q" "bike" "kind" "free" "category" "home"})]
        (is (= 303 (:status response)))
        (is (= "/my/searches?saved" (get-in response [:headers "location"]))))
      (let [body (:body (request :get "/my/searches?saved" alice))]
        (is (str/includes? body "“bike”, free, in Furniture &amp; home"))
        (is (str/includes? body "href=\"/listings?q=bike&amp;kind=free&amp;category=home\""))
        (is (str/includes? body "Saved."))))
    (testing "unknown filters are dropped, nothing left is refused"
      (is (= 422 (:status (save alice {"kind" "bogus" "category" "<x>" "q" "***"})))))
    (testing "they are private"
      (is (not (str/includes? (:body (request :get "/my/searches" bob)) "bike"))))
    (testing "removing one"
      (let [id (second (re-find #"action=\"/my/searches/(\d+)/remove\"" (:body (request :get "/my/searches" alice))))]
        (is (= 404 (:status (fixtures/request :post (str "/my/searches/" id "/remove") bob :form {}))))
        (is (= 303 (:status (fixtures/request :post (str "/my/searches/" id "/remove") alice :form {}))))
        (is (str/includes? (:body (request :get "/my/searches" alice)) "No saved searches"))))))

(deftest changed-favorites-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        id (create! alice valid-fields)
        nav #(re-find #"<a href=\"/my/favorites\"[^>]*>.*?</a>" (:body (request :get "/listings" %)))]
    (fixtures/request :post (str "/listings/" id "/favorite") bob :form {"favorite" "on"})
    (testing "no count while nothing changed"
      (is (not (str/includes? (nav bob) "badge"))))
    (post-form (str "/listings/" id "/status") alice {"status" "reserved"})
    (testing "a change shows in the header"
      (is (str/includes? (nav bob) "<span class=\"badge\">1<span class=\"sr-only\"> changed</span></span>")))
    (testing "the favorites page marks it, and seeing it clears the count"
      (is (str/includes? (:body (request :get "/my/favorites" bob)) "<span class=\"changed\">Changed</span>"))
      (is (not (str/includes? (nav bob) "badge")))
      (is (not (str/includes? (:body (request :get "/my/favorites" bob)) "<span class=\"changed\">"))))))

(deftest heart-comes-back-to-the-page-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")]
    (create! alice valid-fields)
    (testing "a heart on a later board page brings a plain form back to that page"
      (is (re-find #"id=\"card-favorite-\d+\"(?:(?!</form>).)*name=\"return_to\" value=\"/listings\?before=9999999999999\.1\""
                   (:body (request :get "/listings?before=9999999999999.1" bob)))))))

(deftest file-where-text-was-expected-test
  (testing "a file sent as a photo to remove is ignored, not a failure"
    (let [alice (sign-in! "alice@example.com")
          id (create! alice valid-fields)
          response (request :post (str "/listings/" id) alice
                            :fields (assoc valid-fields "title" "Floor lamp")
                            :files [["remove" "1" (.getBytes "1")]])]
      (is (= 303 (:status response)))
      (is (= "Floor lamp" (:title (listing/by-id *db* id)))))))

(deftest member-page-test
  (let [token (sign-in! "alice@example.com")
        bob (fixtures/user! "bob")
        carol (fixtures/user! "carol")
        lamp (fixtures/listing! bob "Lamp")
        chair (fixtures/listing! bob "Chair" :kind "sale")
        sold (fixtures/listing! bob "Sold bike" :kind "sale")
        _ (listing/set-status! *db* (:id sold) bob "sold" 1)
        _ (listing/set-status! *db* (:id chair) bob "reserved" 1)
        _ (fixtures/listing! carol "Carol's desk")
        page #(fixtures/request :get (str "/members/" %) token)
        {:keys [status body]} (page bob)]
    (is (= 200 status))
    (testing "the member's name, and what they have on the board"
      (is (str/includes? body "<h1>bob</h1>"))
      (is (= #{"Lamp" "Chair"} (set (card-titles body)))))
    (testing "nothing of theirs that left the board, nor anyone else's"
      (is (not (str/includes? body "Sold bike")))
      (is (not (str/includes? body "Carol&#39;s desk"))))
    (testing "an unknown or deleted member is not found"
      (is (= 404 (:status (page 999))))
      (is (= 404 (:status (page "x")))))
    (testing "signed in only"
      (is (= 302 (:status (fixtures/request :get (str "/members/" bob) nil)))))
    (testing "the owner's name on a listing leads there"
      (is (str/includes? (:body (fixtures/request :get (str "/listings/" (:id lamp)) token))
                         (str "<a href=\"/members/" bob "\">bob</a>"))))))

(deftest conditional-fields-test
  (let [alice (sign-in! "alice@example.com")
        new-form (:body (request :get "/listings/new" alice))
        price-field #(re-find #"<div [^>]*data-show=\"\$kind === &apos;sale&apos;\"[^>]*>" %)
        city-field #(re-find #"<div [^>]*data-show=\"\$pickup === &apos;city&apos;\"[^>]*>" %)]
    (testing "fields that do not apply are hidden from the start, not only once scripts run"
      (is (not (str/includes? (price-field new-form) "display: none")) "for sale, the price")
      (is (str/includes? (city-field new-form) "display: none") "picked up at a place, no city")
      (let [id (create! alice (assoc valid-fields "kind" "free" "pickup" "city" "city" "Bern"))
            edit (:body (request :get (str "/listings/" id "/edit") alice))]
        (is (str/includes? (price-field edit) "display: none") "given away, no price")
        (is (not (str/includes? (city-field edit) "display: none")))))))

(deftest invalid-choices-test
  (let [alice (sign-in! "alice@example.com")
        {:keys [status body]} (request :post "/listings" alice :fields (assoc valid-fields "kind" "barter" "pickup" "nowhere"))]
    (testing "a choice refused marks its options invalid, pointing at the message"
      (is (= 422 status))
      (is (re-find #"<input [^>]*name=\"kind\"[^>]*aria-invalid=\"true\"[^>]*aria-describedby=\"kind-error\"" body))
      (is (re-find #"<input [^>]*name=\"pickup\"[^>]*aria-invalid=\"true\"[^>]*aria-describedby=\"pickup-error\"" body))
      (is (str/includes? body "id=\"kind-error\""))
      (is (str/includes? body "id=\"pickup-error\"")))))

(deftest photo-order-test
  (let [alice (sign-in! "alice@example.com")
        png (fixture-bytes "transparent.png")
        id (let [response (request :post "/listings" alice :fields valid-fields
                                   :files (for [i (range 3)] ["photos" (str "p" i ".png") png]))]
             (parse-long (second (re-find #"/listings/(\d+)" (get-in response [:headers "location"])))))
        ids #(mapv :id (:photos (listing/by-id *db* id)))
        [a b c] (ids)
        save! (fn [& pairs] (request :post (str "/listings/" id) alice :fields (into (vec valid-fields) (partition 2 pairs))))]
    (testing "the edit form lists the photos in order, each with its place and a choice of cover"
      (let [body (:body (request :get (str "/listings/" id "/edit") alice))]
        (is (= (map str [a b c]) (map second (re-seq #"<input type=\"hidden\" name=\"order\" value=\"(\d+)\"" body))))
        (is (re-find (re-pattern (str "<input type=\"radio\" name=\"cover\" value=\"" a "\" checked")) body))))
    (testing "saved in the order the form sends"
      (is (= 303 (:status (save! "order" c "order" a "order" b "cover" c))))
      (is (= [c a b] (ids))))
    (testing "without scripts, the cover chosen comes first, the others as they were"
      (save! "cover" b)
      (is (= [b c a] (ids))))))

(deftest replace-all-photos-test
  (let [alice (sign-in! "alice@example.com")
        png (fixture-bytes "transparent.png")
        photos #(for [i (range listing/max-photos)] ["photos" (str "p" i ".png") png])
        id (let [response (request :post "/listings" alice :fields valid-fields :files (photos))]
             (parse-long (second (re-find #"/listings/(\d+)" (get-in response [:headers "location"])))))
        old (mapv :id (:photos (listing/by-id *db* id)))
        old-keys (set (map :object-key (:photos (listing/by-id *db* id))))]
    (testing "every photo of a full listing replaced at once, the order and cover sent along"
      (let [response (request :post (str "/listings/" id) alice
                              :fields (concat valid-fields
                                              (for [p old] ["order" p])
                                              [["cover" (first old)]]
                                              (for [p old] ["remove" p]))
                              :files (photos))]
        (is (= 303 (:status response)))
        (is (= listing/max-photos (count (:photos (listing/by-id *db* id)))))
        (is (not-any? old-keys (map :object-key (:photos (listing/by-id *db* id)))))))))
