(ns s-exp.jumble.account.membership-test
  "Email patterns: parsing them, and the addresses they let in."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.ex :as ex]
            [s-exp.jumble.account.membership :as membership]
            [s-exp.jumble.error :as-alias error]))

(set! *warn-on-reflection* true)

(defn- allows?
  [patterns email]
  (membership/allows? (membership/parse patterns) email))

(deftest parse-test
  (testing "comma separated, or a list; spacing and case ignored"
    (is (= ["*@example.org" "bob@gmail.com"] (membership/parse " *@Example.org, BOB@gmail.com ")))
    (is (= ["*@example.org"] (membership/parse ["*@example.org"]))))
  (testing "* alone lets anyone in"
    (is (= :anyone (membership/parse "*")))
    (is (= :anyone (membership/parse ["*@example.org" "*"]))))
  (testing "patterns that would surprise are refused"
    (doseq [bad ["" " , " "example.org" "*@*" "a*@example.org" "*@example.*" "*@*example.org"
                 "alice@" "@example.org" "a@b@c.org"
                 ;; Whole top-level domains, or hosts without a domain.
                 "*@*.com" "*@com" "bob@localhost"]]
      (is (= ::error/invalid-pattern
             (try
               (membership/parse bad)
               nil
               (catch clojure.lang.ExceptionInfo e
                 (ex/ex-type e))))
          bad))))

(deftest address-test
  (testing "one address, which a list of patterns reads as itself"
    (is (membership/address? "bob@gmail.com"))
    (is (not (membership/address? "*@gmail.com")))
    (is (not (membership/address? "a*b@gmail.com")))
    (is (not (membership/address? nil)))))

(deftest allows-test
  (testing "fully open"
    (is (allows? "*" "anyone@anywhere.net")))
  (testing "a domain"
    (is (allows? "*@example.org" "alice@example.org"))
    (is (allows? "*@example.org" "Alice@EXAMPLE.org"))
    (is (not (allows? "*@example.org" "alice@sub.example.org")))
    (is (not (allows? "*@example.org" "alice@example.org.evil.net")))
    (is (not (allows? "*@example.org" "alice@notexample.org"))))
  (testing "a domain and its subdomains"
    (is (allows? "*@*.example.org" "alice@sub.example.org"))
    (is (allows? "*@*.example.org" "alice@a.b.example.org"))
    (is (not (allows? "*@*.example.org" "alice@example.org")) "list *@example.org as well for that")
    (is (not (allows? "*@*.example.org" "alice@evilexample.org"))))
  (testing "single addresses"
    (is (allows? "bob@gmail.com" "Bob@Gmail.com"))
    (is (not (allows? "bob@gmail.com" "rob@gmail.com"))))
  (testing "several rules: any one is enough"
    (is (allows? "*@example.org, bob@gmail.com" "bob@gmail.com"))
    (is (not (allows? "*@example.org, bob@gmail.com" "eve@gmail.com"))))
  (testing "dots and other characters in patterns are literal"
    (is (not (allows? "*@example.org" "alice@exampleXorg")))))
