(ns s-exp.jumble.message.model-test
  "Conversations: starting one about a listing, replies, reading, and the
  notifications they schedule."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.fixtures :refer [*db* db-fixture listing! user!]]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.message.model :as message]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(defn- start!
  [sender lamp body at]
  (message/start! *db* {:sender-id sender :listing-id (:id lamp) :subject (:title lamp) :body body :now at}))

(defn- latest-id
  [thread-id user-id]
  (:id (peek (:messages (message/thread *db* thread-id user-id)))))

(deftest validate-test
  (is (= {:message {:subject "About the lamp" :body "Still available?"}}
         (message/validate {:subject "  About the lamp " :body " Still available?\n"})))
  (is (= #{:subject :body} (set (keys (:errors (message/validate {:subject " " :body ""}))))))
  (is (= #{:body} (set (keys (:errors (message/validate {:subject "x" :body (apply str (repeat 2001 "x"))})))))))

(deftest start-test
  (let [alice (user! "alice") bob (user! "bob") carol (user! "carol")
        lamp (listing! alice "Desk lamp")
        thread-id (start! bob lamp "Still available?" 100)]
    (testing "a first message opens a thread about the listing between the two"
      (is (integer? thread-id))
      (let [t (message/thread *db* thread-id bob)]
        (is (= ["Desk lamp" "Desk lamp" (:id lamp)] ((juxt :subject :post-title :listing-id) t)))
        (is (= "alice" (get-in t [:other :name])))
        (is (= [["bob" "Still available?"]] (mapv (juxt :sender-name :body) (:messages t))))))
    (testing "the sender has read it, the owner not yet"
      (is (= 0 (message/unread-count *db* bob)))
      (is (= 1 (message/unread-count *db* alice))))
    (testing "writing again about the same listing continues the thread"
      (is (= thread-id (start! bob lamp "Can I pick it up today?" 200)))
      (is (= 2 (count (:messages (message/thread *db* thread-id alice))))))
    (testing "another person gets their own thread"
      (is (not= thread-id (start! carol lamp "Interested too" 300))))
    (testing "no messages to yourself"
      (is (nil? (start! alice lamp "Note to self" 400))))
    (testing "no new conversations about listings no longer on offer"
      (let [chair (listing! alice "Chair" :kind "sale")]
        (listing/set-status! *db* (:id chair) alice "sold" 500)
        (is (nil? (start! bob (listing/by-id *db* (:id chair)) "Sold already?" 600)))))))

(deftest reply-and-read-test
  (let [alice (user! "alice") bob (user! "bob") carol (user! "carol")
        lamp (listing! alice "Desk lamp")
        thread-id (start! bob lamp "Still available?" 100)]
    (testing "participants reply; others cannot"
      (is (integer? (message/reply! *db* thread-id alice "Yes!" 200)))
      (is (nil? (message/reply! *db* thread-id carol "Me too" 300)))
      (is (= ["bob" "alice"] (mapv :sender-name (:messages (message/thread *db* thread-id bob))))))
    (testing "only participants can read the thread"
      (is (nil? (message/thread *db* thread-id carol))))
    (testing "reading marks the thread read for that person only"
      (is (= 1 (message/unread-count *db* bob)))
      (message/mark-read! *db* thread-id bob (latest-id thread-id bob))
      (is (= 0 (message/unread-count *db* bob)))
      (is (= 0 (message/unread-count *db* alice)) "alice wrote the latest message"))))

(deftest inbox-test
  (let [alice (user! "alice") bob (user! "bob") carol (user! "carol")
        lamp (listing! alice "Desk lamp")
        chair (listing! alice "Chair")
        first-thread (start! bob lamp "About the lamp" 100)
        second-thread (start! carol chair "About the chair" 200)]
    (message/reply! *db* first-thread alice "Yes, still there" 300)
    (testing "threads by latest activity, with the other person, the last message and unread counts"
      (is (= [{:id first-thread :subject "Desk lamp" :post-title "Desk lamp" :listing-id (:id lamp)
               :other-id bob :other-name "bob" :other-email "bob@example.com" :last-body "Yes, still there" :last-message-at 300 :unread 0}
              {:id second-thread :subject "Chair" :post-title "Chair" :listing-id (:id chair)
               :other-id carol :other-name "carol" :other-email "carol@example.com" :last-body "About the chair" :last-message-at 200 :unread 1}]
             (message/inbox *db* alice)))
      (is (= [first-thread] (mapv :id (message/inbox *db* bob))))
      (is (= 1 (:unread (first (message/inbox *db* bob))))))))

(deftest removed-listing-test
  (let [alice (user! "alice") bob (user! "bob")
        lamp (listing! alice "Desk lamp")
        thread-id (start! bob lamp "Still available?" 100)]
    (listing/delete! *db* (blob/memory-store) (:id lamp) alice)
    (testing "the conversation stays, saying what it was about"
      (let [t (message/thread *db* thread-id bob)]
        (is (= [nil "Desk lamp" 1] [(:listing-id t) (:post-title t) (count (:messages t))])))
      (is (integer? (message/reply! *db* thread-id alice "It's gone, sorry" 200))))))

(deftest mark-read-test
  (let [alice (user! "alice") bob (user! "bob")
        thread-id (start! bob (listing! alice "Desk lamp") "Still available?" 100)
        shown (latest-id thread-id alice)]
    (testing "reading marks only the messages that were shown"
      (message/reply! *db* thread-id bob "Hello?" 200)
      (message/mark-read! *db* thread-id alice shown)
      (is (= 1 (message/unread-count *db* alice))))
    (testing "and never moves back"
      (message/mark-read! *db* thread-id alice (latest-id thread-id alice))
      (message/mark-read! *db* thread-id alice shown)
      (is (= 0 (message/unread-count *db* alice))))))

(deftest start-checks-the-listing-test
  (testing "the listing is checked as it is when the message is sent, not as it was loaded"
    (let [alice (user! "alice") bob (user! "bob")
          chair (listing! alice "Chair" :kind "sale")]
      (listing/set-status! *db* (:id chair) alice "sold" 500)
      (is (nil? (start! bob chair "Still there?" 600)))
      (listing/delete! *db* (blob/memory-store) (:id chair) alice)
      (is (nil? (start! bob chair "Still there?" 700))))))

(deftest notification-scheduling-test
  (let [alice (user! "alice") bob (user! "bob")
        thread-id (start! bob (listing! alice "Desk lamp") "Still available?" 100)
        pending #(jdbc/execute! *db* ["SELECT user_id, due_at, attempts FROM notification"]
                                {:builder-fn rs/as-unqualified-maps})]
    (testing "a message schedules an email to the other participant only, 5 minutes later"
      (is (= [{:user_id alice :due_at 300100 :attempts 0}] (pending))))
    (testing "a newer message keeps the time and starts failed attempts over"
      (jdbc/execute! *db* ["UPDATE notification SET attempts = 9"])
      (message/reply! *db* thread-id bob "Hello?" 200)
      (is (= [{:user_id alice :due_at 300100 :attempts 0}] (pending))))))

(deftest current-title-test
  (testing "threads show the listing's current title"
    (let [alice (user! "alice") bob (user! "bob")
          lamp (listing! alice "Desk lamp")
          thread-id (start! bob lamp "Still available?" 100)]
      (jdbc/execute! *db* ["UPDATE listing SET title = 'Brass desk lamp' WHERE id = ?" (:id lamp)])
      (is (= "Brass desk lamp" (:post-title (message/thread *db* thread-id bob))))
      (is (= "Brass desk lamp" (:post-title (first (message/inbox *db* bob))))))))
