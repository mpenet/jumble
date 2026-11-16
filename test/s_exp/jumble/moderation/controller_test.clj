(ns s-exp.jumble.moderation.controller-test
  "Moderation on the web: reports, removing and dismissing, categories and
  places, the moderators' pages."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture request sign-in!]]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.page.palette :as palette]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(defn- listing!
  [owner-email title]
  (:id (fixtures/listing! (fixtures/user-id owner-email) title)))

(defn- outbox
  []
  (jdbc/execute! *db*
                 ["SELECT recipient, subject, body FROM email_outbox ORDER BY id"]
                 {:builder-fn rs/as-unqualified-maps}))

(defn- location [response] (get-in response [:headers "location"]))

(deftest report-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        id (listing! "alice@example.com" "Desk lamp")
        page (str "/listings/" id)
        form (str "action=\"/listings/" id "/report\"")]
    (testing "others can report a listing; its owner cannot"
      (is (str/includes? (:body (request :get page bob)) form))
      (is (not (str/includes? (:body (request :get page alice)) form)))
      (is (= 404 (:status (request :post (str page "/report") alice :form {"reason" "x"})))))
    (testing "reporting thanks the reporter and alerts the moderators"
      (let [response (request :post (str page "/report") bob :form {"reason" "Looks <b>fake</b>"})]
        (is (= 303 (:status response)))
        (is (= (str page "?reported") (location response))))
      (let [body (:body (request :get (str page "?reported") bob))]
        (is (str/includes? body "Thanks, a moderator will have a look."))
        (is (not (str/includes? body form))))
      (let [[{:keys [recipient subject body]}] (outbox)]
        (is (= "dave@example.com" recipient))
        (is (= "Listing reported: Desk lamp" subject))
        (is (str/includes? body "> Looks <b>fake</b>"))
        (is (str/includes? body "http://localhost:8080/manage"))))
    (testing "a reason too long comes back with an explanation"
      (let [{:keys [status body]} (request :post
                                           (str page "/report")
                                           (sign-in! "carol@example.com")
                                           :form {"reason" (apply str (repeat 1001 "x"))})]
        (is (= 422 status))
        (is (str/includes? body "Keep it to 1000 characters or fewer."))))
    (testing "listings that are gone cannot be reported"
      (is (= 404 (:status (request :post "/listings/999/report" bob :form {})))))))

(deftest moderation-page-test
  (let [_ (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        dave (sign-in! "dave@example.com")
        id (listing! "alice@example.com" "Desk lamp")]
    (request :post (str "/listings/" id "/report") bob :form {"reason" "Looks <b>fake</b>"})
    (testing "only moderators see the reports, and the way to them"
      (is (= 404 (:status (request :get "/manage" bob))))
      (is (not (str/includes? (:body (request :get "/listings" bob)) "href=\"/manage\"")))
      (is (re-find #"<a [^>]*href=\"/manage\"[^>]*>.*?<span>Manage</span><span class=\"badge\">1<span class=\"sr-only\"> to review</span></span></a>"
                   (:body (request :get "/listings" dave)))))
    (testing "the page lists reported listings with their reports, escaped"
      (let [{:keys [status body]} (request :get "/manage" dave)]
        (is (= 200 status))
        (is (str/includes? body (str "href=\"/listings/" id "\"")))
        (is (str/includes? body "Looks &lt;b&gt;fake&lt;/b&gt;"))
        (is (str/includes? body "bob@example.com"))
        (is (str/includes? body (str "action=\"/manage/listings/" id "/remove\"")))
        (is (str/includes? body (str "action=\"/manage/listings/" id "/dismiss\"")))))))

(deftest remove-test
  (let [_ (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        dave (sign-in! "dave@example.com")
        id (listing! "alice@example.com" "Desk lamp")]
    (request :post (str "/listings/" id "/report") bob :form {"reason" "Spam"})
    (testing "only moderators can remove"
      (is (= 404 (:status (request :post (str "/manage/listings/" id "/remove") bob :form {"reason" "x"}))))
      (is (some? (listing/by-id *db* id))))
    (testing "a moderator removes the listing and the owner is told why"
      (let [removed (fixtures/counted "jumble_listings_closed_total{outcome=\"removed\"}")
            response (request :post (str "/manage/listings/" id "/remove") dave :form {"reason" "Not allowed here"})]
        (is (= 1.0 (- (fixtures/counted "jumble_listings_closed_total{outcome=\"removed\"}") removed)))
        (is (= 303 (:status response)))
        (is (= "/manage" (location response))))
      (is (nil? (listing/by-id *db* id)))
      (let [{:keys [recipient subject body]} (last (outbox))]
        (is (= "alice@example.com" recipient))
        (is (= "Your listing was removed: Desk lamp" subject))
        (is (str/includes? body "> Not allowed here")))
      (is (str/includes? (:body (request :get "/manage" dave)) "No open reports")))))

(deftest dismiss-test
  (let [_ (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        dave (sign-in! "dave@example.com")
        id (listing! "alice@example.com" "Desk lamp")]
    (request :post (str "/listings/" id "/report") bob :form {"reason" "Spam"})
    (is (= 404 (:status (request :post (str "/manage/listings/" id "/dismiss") bob :form {}))))
    (is (= 303 (:status (request :post (str "/manage/listings/" id "/dismiss") dave :form {}))))
    (is (some? (listing/by-id *db* id)))
    (is (= 1 (count (outbox))) "the owner is not told")
    (is (str/includes? (:body (request :get "/manage" dave)) "No open reports"))))

(deftest remove-any-listing-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        dave (sign-in! "dave@example.com")
        id (listing! "alice@example.com" "Desk lamp")
        page (str "/listings/" id)
        confirm (str "/manage/listings/" id "/remove")
        link (str "href=\"" confirm "\"")]
    (testing "moderators can remove any listing from its page; others cannot"
      (is (str/includes? (:body (request :get page dave)) link))
      (is (not (str/includes? (:body (request :get page bob)) link)))
      (is (not (str/includes? (:body (request :get page alice)) link)) "owners remove their own the usual way")
      (is (= 404 (:status (request :get confirm bob))))
      (is (= 404 (:status (request :post confirm bob :form {"reason" "x"})))))
    (testing "after confirming, with a reason for the owner"
      (let [{:keys [status body]} (request :get confirm dave)]
        (is (= 200 status))
        (is (str/includes? body "Desk lamp"))
        (is (str/includes? body (str "action=\"" confirm "\"")))
        (is (str/includes? body (str "href=\"" page "\"")) "or back to the listing"))
      (let [response (request :post confirm dave :form {"reason" "Not allowed here"})]
        (is (= 303 (:status response)))
        (is (= "/manage" (location response))))
      (is (nil? (listing/by-id *db* id)))
      (let [{:keys [recipient body]} (last (outbox))]
        (is (= "alice@example.com" recipient))
        (is (str/includes? body "> Not allowed here"))))
    (testing "a listing already gone"
      (is (= 404 (:status (request :get confirm dave)))))))

(deftest typed-reasons-kept-test
  (let [_ (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        dave (sign-in! "dave@example.com")
        id (listing! "alice@example.com" "Desk lamp")
        too-long (apply str (repeat 1001 "x"))]
    (testing "a report reason too long comes back on a report form, as typed"
      (let [{:keys [status body]} (request :post (str "/listings/" id "/report") bob :form {"reason" too-long})]
        (is (= 422 status))
        (is (str/includes? body (str ">" too-long "</textarea>")))
        (is (str/includes? body (str "action=\"/listings/" id "/report\"")))))
    (testing "a removal reason too long comes back on the confirmation page, as typed"
      (let [{:keys [status body]} (request :post (str "/manage/listings/" id "/remove") dave :form {"reason" too-long})]
        (is (= 422 status))
        (is (str/includes? body (str ">" too-long "</textarea>")))
        (is (str/includes? body "Keep it to 1000 characters or fewer."))))))

(deftest moderation-edges-test
  (let [_ (sign-in! "alice@example.com")
        dave (sign-in! "dave@example.com")
        own (listing! "dave@example.com" "Dave's chair")]
    (testing "dismissing unknown listings finds nothing"
      (is (= 404 (:status (request :post "/manage/listings/999/dismiss" dave :form {}))))
      (is (= 404 (:status (request :post "/manage/listings/abc/dismiss" dave :form {})))))
    (testing "moderators remove their own listings as owners, not as moderators"
      (is (= 404 (:status (request :get (str "/manage/listings/" own "/remove") dave))))
      (is (= 404 (:status (request :post (str "/manage/listings/" own "/remove") dave :form {"reason" ""})))))
    (testing "nor dismiss the reports on them: another moderator does"
      (request :post (str "/listings/" own "/report") (sign-in! "bob@example.com") :form {"reason" "Spam"})
      (is (= 404 (:status (request :post (str "/manage/listings/" own "/dismiss") dave :form {}))))
      (let [body (:body (request :get "/manage" dave))]
        (is (str/includes? body "Dave's chair") "still open")
        (is (not (str/includes? body (str "/manage/listings/" own "/dismiss"))))
        (is (not (str/includes? body (str "/manage/listings/" own "/remove"))))
        (is (str/includes? body "Your own listing: another moderator handles its reports."))))))

(deftest distinct-buttons-test
  (testing "repeated buttons say what they act on"
    (let [_ (sign-in! "alice@example.com")
          bob (sign-in! "bob@example.com")
          dave (sign-in! "dave@example.com")
          id (listing! "alice@example.com" "Desk lamp")]
      (request :post (str "/listings/" id "/report") bob :form {"reason" "Spam"})
      (let [body (:body (request :get "/manage" dave))]
        (is (str/includes? body "aria-label=\"Remove listing Desk lamp\""))
        (is (str/includes? body "aria-label=\"Dismiss the reports on Desk lamp\""))))))

(deftest double-submit-guard-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        dave (sign-in! "dave@example.com")
        id (listing! "alice@example.com" "Desk lamp")
        pages {bob [(str "/listings/" id)]
               alice ["/listings/new" (str "/listings/" id "/edit") (str "/listings/" id "/remove")]
               dave ["/manage/members" "/manage/categories" "/manage/places"
                     (str "/manage/listings/" id "/remove")]}]
    (testing "forms ignore a second click while the first submit is on its way"
      (is (re-find #"<form [^>]*action=\"/listings/\d+/report\"[^>]*data-on:click=\"[^\"]*dataset.sent"
                   (:body (request :get (str "/listings/" id) bob)))))
    (testing "no form sent by the browser handles submit with Datastar, which would cancel the submission"
      (doseq [[token paths] pages
              path paths
              form (re-seq #"<form [^>]*>" (:body (request :get path token)))
              :when (str/includes? form "data-on:submit=")]
        (is (re-find #"data-on:submit=\"[^\"]*@(get|post)\(" form) (str path ": " form))))))

(defn- order-of
  "Labels of `labels` in the order they appear in `body`."
  [body labels]
  (sort-by #(str/index-of body %) labels))

(deftest categories-test
  (let [dave (sign-in! "dave@example.com")
        bob (sign-in! "bob@example.com")
        page #(:body (request :get "/manage/categories" dave))]
    (listing! "bob@example.com" "Desk lamp")
    (testing "for moderators only"
      (is (= 404 (:status (request :get "/manage/categories" bob))))
      (is (= 404 (:status (request :post "/manage/categories" bob :form {"label" "Books"})))))
    (testing "the categories in order, each with its number of listings"
      (let [body (page)]
        (is (str/includes? body "value=\"Furniture &amp; home\""))
        (is (str/includes? body "1 listing"))
        (is (str/includes? body "No listings"))
        (is (= ["Furniture" "Other"] (order-of body ["Other" "Furniture"])))))
    (testing "adding one"
      (let [response (request :post "/manage/categories" dave :form {"label" "Books"})]
        (is (= 303 (:status response)))
        (is (= "/manage/categories" (location response))))
      (is (str/includes? (page) "value=\"Books\""))
      (let [{:keys [status body]} (request :post "/manage/categories" dave :form {"label" "books"})]
        (is (= 422 status))
        (is (str/includes? body "There is already one named “Books”."))
        (is (str/includes? body "value=\"books\"") "what was typed is kept")))
    (testing "renaming one"
      (is (= 303 (:status (request :post "/manage/categories/books/rename" dave :form {"label" "Comics"}))))
      (is (str/includes? (page) "value=\"Comics\""))
      (let [{:keys [status body]} (request :post "/manage/categories/books/rename" dave :form {"label" "Other"})]
        (is (= 422 status))
        (is (str/includes? body "There is already one named “Other”."))))
    (testing "moving one"
      (is (= 303 (:status (request :post "/manage/categories/books/move" dave :form {"direction" "up"}))))
      (is (= ["Furniture" "Comics" "Other"] (order-of (page) ["Other" "Comics" "Furniture"]))))
    (testing "retiring one: no longer offered, its listings keep it"
      (is (= 303 (:status (request :post "/manage/categories/home/retire" dave))))
      (is (str/includes? (page) "action=\"/manage/categories/home/restore\""))
      (is (not (str/includes? (:body (request :get "/listings/new" bob)) "value=\"home\"")))
      (is (str/includes? (:body (request :get "/listings" bob)) "Furniture &amp; home")))
    (testing "and restoring it"
      (is (= 303 (:status (request :post "/manage/categories/home/restore" dave))))
      (is (str/includes? (:body (request :get "/listings/new" bob)) "value=\"home\"")))
    (testing "the last category stays"
      (request :post "/manage/categories/books/retire" dave)
      (request :post "/manage/categories/home/retire" dave)
      (let [{:keys [status body]} (request :post "/manage/categories/other/retire" dave)]
        (is (= 422 status))
        (is (str/includes? body "Keep at least one category."))))
    (testing "unknown ones"
      (is (= 404 (:status (request :post "/manage/categories/nope/rename" dave :form {"label" "X"}))))
      (is (= 404 (:status (request :post "/manage/categories/nope/retire" dave)))))))

(deftest places-test
  (let [dave (sign-in! "dave@example.com")
        bob (sign-in! "bob@example.com")]
    (testing "added places are offered for pickup"
      (is (= 303 (:status (request :post "/manage/places" dave :form {"label" "Library"}))))
      (is (str/includes? (:body (request :get "/listings/new" bob)) "value=\"place:library\"")))
    (testing "all of them can go: pickup is then in a city"
      (request :post "/manage/places/library/retire" dave)
      (is (= 303 (:status (request :post "/manage/places/hall/retire" dave))))
      (is (not (str/includes? (:body (request :get "/listings/new" bob)) "value=\"place:"))))))

(deftest moderation-navigation-test
  (let [dave (sign-in! "dave@example.com")]
    (doseq [[path current] [["/manage" "Reports"] ["/manage/members" "Who may sign in"]
                            ["/manage/categories" "Categories"] ["/manage/places" "Pickup places"]
                            ["/manage/appearance" "Appearance"] ["/manage/poster" "Poster"]]]
      (let [body (:body (request :get path dave))]
        (testing path
          (is (str/includes? body (str "<a href=\"" path "\" aria-current=\"page\">" current "</a>")))
          (is (str/includes? body "<a href=\"/manage/places\"")))))))

(deftest poster-test
  (let [dave (sign-in! "dave@example.com")
        alice (sign-in! "alice@example.com")]
    (testing "a poster to print: the community's name, what it is for, and a code to scan that opens it"
      (let [{:keys [status body]} (request :get "/manage/poster" dave)]
        (is (= 200 status))
        (is (str/includes? body "<article class=\"poster\""))
        (is (str/includes? body "Give your things a"))
        (is (str/includes? body "Sell, give away or lend what you no longer need"))
        (is (re-find #"<svg class=\"qr\"[^>]*aria-label=\"Scan to open Jumble\"" body))
        (is (str/includes? body ">localhost:8080<") "its address written out, for those who type it")))
    (testing "printed in another language"
      (let [body (:body (request :get "/manage/poster?language=fr" dave))]
        (is (str/includes? body "Offrez une"))
        (is (str/includes? body "lang=\"fr\"")))
      (is (str/includes? (:body (request :get "/manage/poster?language=xx" dave)) "Give your things a")
          "one Jumble does not speak is the community's"))
    (testing "for moderators only"
      (is (= 404 (:status (request :get "/manage/poster" alice)))))))

(deftest appearance-test
  (let [dave (sign-in! "dave@example.com")
        alice (sign-in! "alice@example.com")
        palette-of #(re-find #"data-palette=\"[a-z-]+\"" (:body (request :get % %2)))]
    (testing "forest until moderators choose"
      (is (= "data-palette=\"forest\"" (palette-of "/listings" alice))))
    (testing "only moderators see the page"
      (is (= 404 (:status (request :get "/manage/appearance" alice)))))
    (testing "it offers every palette, the current one chosen"
      (let [body (:body (request :get "/manage/appearance" dave))]
        (doseq [p palette/names]
          (is (str/includes? body (str "name=\"palette\" value=\"" p "\"")) p))
        (is (str/includes? body "value=\"forest\" checked"))))
    (testing "a moderator's choice colours every page, signed in or not"
      (let [response (request :post "/manage/appearance" dave :form {"palette" "tokyo"})]
        (is (= 303 (:status response)))
        (is (= "/manage/appearance?saved" (location response))))
      (is (= "data-palette=\"tokyo\"" (palette-of "/listings" alice)))
      (is (= "data-palette=\"tokyo\"" (palette-of "/privacy" nil)))
      (is (str/includes? (:body (request :get "/manage/appearance?saved" dave)) "value=\"tokyo\" checked")))
    (testing "members cannot choose"
      (is (= 404 (:status (request :post "/manage/appearance" alice :form {"palette" "nord"}))))
      (is (= "data-palette=\"tokyo\"" (palette-of "/privacy" nil))))
    (testing "an unknown palette is refused, the choice kept"
      (is (= 400 (:status (request :post "/manage/appearance" dave :form {"palette" "pink"}))))
      (is (= "data-palette=\"tokyo\"" (palette-of "/privacy" nil))))))

(deftest moderator-notice-test
  (let [dave (sign-in! "dave@example.com")
        f (doto (java.io.File/createTempFile "notice" ".md") (.deleteOnExit))
        page #(:body (request :get % dave :ctx {:settings {:moderator-notice (str f)}}))]
    (spit f "You are at **23 of 25** members: [upgrade](https://jumbl.ing/billing)")
    (testing "the notice file's text is shown on every moderation page, read when shown"
      (doseq [uri ["/manage" "/manage/members" "/manage/categories" "/manage/places"]]
        (is (str/includes? (page uri) "You are at <strong>23 of 25</strong> members") uri)
        (is (str/includes? (page uri) "href=\"https://jumbl.ing/billing\"") uri)))
    (testing "and goes with the file"
      (.delete f)
      (is (not (str/includes? (page "/manage") "moderator-notice"))))))
