(ns s-exp.jumble.email.mail-test
  "Email: addresses accepted, and failures reported without their contents."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.email.mail :as mail]))

(set! *warn-on-reflection* true)

(deftest failure-test
  (testing "what is logged of a failed send names no address"
    (is (= {:error "clojure.lang.ExceptionInfo" :message "550 No such user: …@example.org, <…@example.com>"}
           (mail/failure (ex-info "550 No such user: alice@example.org, <bob@example.com>" {}))))))

(deftest plain-address-test
  (is (mail/plain-address? "alice+jumble@example.org"))
  (doseq [bad ["a,b@example.com" "eve@.example.com" "x%y@example.com" "Eve <eve@example.com>" "eve@localhost" nil]]
    (is (not (mail/plain-address? bad)) (pr-str bad))))
