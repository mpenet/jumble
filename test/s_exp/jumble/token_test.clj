(ns s-exp.jumble.token-test
  "Random tokens and their digests."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.token :as token]))

(deftest generate-test
  (let [tokens (repeatedly 100 token/generate)]
    (testing "256 bits, base64url without padding"
      (is (every? #(re-matches #"[A-Za-z0-9_-]{43}" %) tokens)))
    (testing "unique"
      (is (= 100 (count (set tokens)))))))

(deftest digest-test
  (testing "SHA-256 of the UTF-8 bytes"
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (apply str (map #(format "%02x" %) (token/digest "abc")))))))

(deftest sha256-hex-test
  (testing "lowercase hex of the SHA-256 of the bytes"
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (token/sha256-hex (.getBytes "abc" "UTF-8"))))))

(deftest base64url-test
  (testing "base64url without padding"
    (is (= "-_8" (token/base64url (byte-array [-5 -1]))))))

(deftest same-test
  (testing "equal strings"
    (is (token/same? "abc" "abc")))
  (testing "different ones, nil or not"
    (is (not (token/same? "abc" "abd")))
    (is (not (token/same? "abc" "ab")))
    (is (not (token/same? nil "abc")))
    (is (not (token/same? "abc" nil)))))
