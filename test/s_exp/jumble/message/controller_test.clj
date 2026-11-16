(ns s-exp.jumble.message.controller-test
  "Conversations on the web: writing, the inbox, threads, replies."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.fixtures :as fixtures :refer [*db* db-fixture request sign-in!]]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.message.model :as message]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(defn- listing!
  [owner-email title]
  (:id (fixtures/listing! (fixtures/user-id owner-email) title)))

(defn- location [response] (get-in response [:headers "location"]))

(defn- thread-id-of [response] (some->> (location response) (re-find #"^/messages/(\d+)$") second parse-long))

(deftest listing-page-link-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        id (listing! "alice@example.com" "Desk lamp")
        link (str "href=\"/listings/" id "/message\"")]
    (testing "others can message the owner, within Jumble"
      (let [body (:body (request :get (str "/listings/" id) bob))]
        (is (str/includes? body link))
        (is (str/includes? body "Message alice@example.com"))
        (is (not (str/includes? body "mailto:")))))
    (testing "not on your own listing, nor once it is no longer on offer"
      (is (not (str/includes? (:body (request :get (str "/listings/" id) alice)) link)))
      (listing/set-status! *db* id (fixtures/user-id "alice@example.com") "given" 1)
      (is (not (str/includes? (:body (request :get (str "/listings/" id) bob)) link))))))

(deftest compose-test
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        id (listing! "alice@example.com" "Desk lamp")
        path (str "/listings/" id "/message")]
    (testing "the form has the listing's title as subject"
      (let [{:keys [status body]} (request :get path bob)]
        (is (= 200 status))
        (is (re-find #"<input [^>]*name=\"subject\" value=\"Desk lamp\"" body))
        (is (re-find #"<textarea [^>]*name=\"body\"" body))))
    (testing "the owner has nobody to write to"
      (is (= 404 (:status (request :get path alice))))
      (is (= 404 (:status (request :post path alice :form {"subject" "x" "body" "y"})))))
    (testing "sending opens the thread"
      (let [sent (fixtures/counted "jumble_messages_sent_total{kind=\"first\"}")
            response (request :post path bob :form {"subject" "Desk lamp" "body" "Still available?"})
            thread-id (thread-id-of response)]
        (is (= 1.0 (- (fixtures/counted "jumble_messages_sent_total{kind=\"first\"}") sent))
            "counted as a conversation started, for activity over time")
        (is (= 303 (:status response)))
        (is (some? thread-id))
        (is (= ["Still available?"] (mapv :body (:messages (message/thread *db* thread-id (fixtures/user-id "bob@example.com"))))))
        (testing "once there is a thread, the form leads to it"
          (is (= (str "/messages/" thread-id) (location (request :get path bob)))))))
    (testing "empty messages come back with an explanation"
      (let [{:keys [status body]} (request :post path (sign-in! "carol@example.com") :form {"subject" "Desk lamp" "body" " "})]
        (is (= 422 status))
        (is (str/includes? body "Write a message."))))
    (testing "invalid input comes back as typed"
      (let [{:keys [status body]} (request :post path (sign-in! "dave@example.com") :form {"subject" " " "body" "Hello there"})]
        (is (= 422 status))
        (is (str/includes? body ">Hello there</textarea>"))))))

(defn- conversation!
  "bob writes to alice about her listing; returns [thread-id alice-token bob-token]."
  []
  (let [alice (sign-in! "alice@example.com")
        bob (sign-in! "bob@example.com")
        id (listing! "alice@example.com" "Desk lamp")
        response (request :post
                          (str "/listings/" id "/message")
                          bob
                          :form {"subject" "Desk lamp" "body" "Still <b>available</b>?"})]
    [(thread-id-of response) alice bob]))

(deftest inbox-and-thread-test
  (let [[thread-id alice bob] (conversation!)]
    (testing "the recipient sees the thread as unread, and a count in the header"
      (let [body (:body (request :get "/messages" alice))]
        (is (re-find (re-pattern (str "<a class=\"thread-row unread\" href=\"/messages/" thread-id "\"")) body))
        (is (str/includes? body "bob@example.com"))
        (is (re-find #"<a [^>]*href=\"/messages\"[^>]*>.*?<span>Messages</span><span class=\"badge\">1<span class=\"sr-only\"> unread</span></span></a>" body))
        (is (str/includes? body "<span class=\"badge\">1<span class=\"sr-only\"> unread</span></span></a></li>"))
        (is (not (str/includes? body "aria-label=\"1 unread\"")))))
    (testing "the thread shows the messages, escaped, and marks them read"
      (let [{:keys [status body]} (request :get (str "/messages/" thread-id) alice)]
        (is (= 200 status))
        (is (str/includes? body "Still &lt;b&gt;available&lt;/b&gt;?"))
        (is (str/includes? body "href=\"/listings/"))
        (testing "with the other member's name leading to their page"
          (is (str/includes? body (str "<a href=\"/members/" (fixtures/user-id "bob@example.com") "\">"))))
        (testing "the header count already reflects reading it"
          (is (not (str/includes? body "<span class=\"badge\">")))))
      (is (= 0 (message/unread-count *db* (fixtures/user-id "alice@example.com")))))
    (testing "others cannot read it"
      (is (= 404 (:status (request :get (str "/messages/" thread-id) (sign-in! "carol@example.com"))))))
    (testing "the sender's own message is not unread for them"
      (is (not (str/includes? (:body (request :get "/messages" bob)) "thread-row unread"))))))

(deftest reply-test
  (let [[thread-id alice] (conversation!)
        path (str "/messages/" thread-id)]
    (testing "a reply is added and leads back to the thread"
      (let [response (request :post path alice :form {"body" "Yes, come by"})]
        (is (= 303 (:status response)))
        (is (str/starts-with? (location response) path)))
      (is (= ["Still <b>available</b>?" "Yes, come by"]
             (mapv :body (:messages (message/thread *db* thread-id (fixtures/user-id "alice@example.com")))))))
    (testing "empty replies come back with an explanation"
      (let [{:keys [status body]} (request :post path alice :form {"body" ""})]
        (is (= 422 status))
        (is (str/includes? body "Write a message."))))
    (testing "a reply too long comes back as typed"
      (let [long-reply (apply str (repeat 2001 "x"))
            {:keys [status body]} (request :post path alice :form {"body" long-reply})]
        (is (= 422 status))
        (is (str/includes? body (str ">" long-reply "</textarea>")))))
    (testing "others cannot write into it"
      (is (= 404 (:status (request :post path (sign-in! "carol@example.com") :form {"body" "Hi"})))))))

(deftest inbox-names-test
  (let [[_ alice] (conversation!)
        bob (fixtures/user-id "bob@example.com")
        avatar #(second (re-find #"class=\"thread-row[^\"]*\"[^>]*><span class=\"avatar avatar-\d\" aria-hidden=\"true\">([^<]*)</span>" %))]
    (testing "a person without a name shows by email"
      (jdbc/execute! *db* ["UPDATE user SET name = '' WHERE id = ?" bob])
      (let [{:keys [status body]} (request :get "/messages" alice)]
        (is (= 200 status))
        (is (= "B" (avatar body)))))
    (testing "initials are letters"
      (jdbc/execute! *db* ["UPDATE user SET name = '\uD83D\uDE00 Smiley' WHERE id = ?" bob])
      (is (= "S" (avatar (:body (request :get "/messages" alice))))))))

(deftest removed-listing-inbox-test
  (testing "the inbox says when the listing a conversation is about was removed"
    (let [[_ alice] (conversation!)
          alice-id (fixtures/user-id "alice@example.com")
          lamp (:id (first (jdbc/execute! *db* ["SELECT id FROM listing"] {:builder-fn rs/as-unqualified-maps})))]
      (listing/delete! *db* (blob/memory-store) lamp alice-id)
      (is (str/includes? (:body (request :get "/messages" alice)) "(removed)")))))
