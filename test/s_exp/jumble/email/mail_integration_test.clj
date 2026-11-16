(ns ^:integration s-exp.jumble.email.mail-integration-test
  "Against the local Mailpit from docker compose."
  (:require [clojure.test :refer [deftest is]]
            [s-exp.oda :as oda]
            [s-exp.jumble.http :as http]
            [s-exp.jumble.email.mail :as mail]))

(set! *warn-on-reflection* true)

(defn- mailpit
  [path]
  (oda/parse ^String (:body (http/request (http/client {:connect-timeout 5000})
                                          {:method :get :url (str "http://127.0.0.1:8025/api/v1" path)}
                                          :string))))

(deftest smtp-mailer-test
  (let [token (str (random-uuid))
        mailer (mail/smtp-mailer {:host "127.0.0.1" :port 1025 :starttls false :from "Jumble <jumble@example.com>"})]
    (mailer {:to "alice@example.com" :subject (str "Hello é " token) :text "Line one\nLine two ü\n"})
    (let [[found] (get (mailpit (str "/search?query=" token)) "messages")
          message (mailpit (str "/message/" (get found "ID")))]
      (is (= (str "Hello é " token) (get message "Subject")))
      (is (= [{"Name" "Jumble" "Address" "jumble@example.com"}] [(get message "From")]))
      (is (= ["alice@example.com"] (mapv #(get % "Address") (get message "To"))))
      (is (= "Line one\r\nLine two ü\r\n" (get message "Text"))))))
