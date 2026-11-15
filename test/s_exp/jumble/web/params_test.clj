(ns s-exp.jumble.web.params-test
  "Reading form fields: trimmed text, single values, ids."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.web.params :as params]))

(set! *warn-on-reflection* true)

(deftest trimmed-test
  (testing "surrounding whitespace goes"
    (is (= "lamp" (params/trimmed "  lamp \n"))))
  (testing "line breaks count as browsers count them: one character"
    (is (= "a\nb\nc" (params/trimmed "a\r\nb\rc"))))
  (testing "control characters go, tabs and line breaks stay"
    (is (= "lamp" (params/trimmed "\u0000lamp\u0007")))
    (is (= "a\tb\nc" (params/trimmed "a\tb\u001b\nc"))))
  (testing "absent stays absent"
    (is (nil? (params/trimmed nil)))))

(deftest one-test
  (testing "a field sent twice reads as its first value, not as a list"
    (is (= "a@example.org" (params/one {"email" ["a@example.org" "b@example.org"]} "email")))
    (is (= "x" (params/one {"email" "x"} "email")))
    (is (nil? (params/one {} "email")))))

(deftest id-test
  (testing "ids from text"
    (is (= 42 (params/id "42")))
    (is (nil? (params/id "4x")))
    (is (nil? (params/id nil))))
  (testing "anything else is not one, a file sent where text was expected say"
    (is (nil? (params/id {:filename "x" :bytes (byte-array 0)})))
    (is (nil? (params/id 42)))))
