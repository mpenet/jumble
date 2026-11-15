(ns s-exp.jumble.store.blob-contract
  "Behaviour every blob store implements, checked against each of them."
  (:require [clojure.test :refer [is testing]]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.token :as token]))

(set! *warn-on-reflection* true)

(defn- read-all
  [{:keys [^java.io.InputStream body]}]
  (with-open [in body] (.readAllBytes in)))

(defn check
  [store]
  (let [key (str "test/" (token/generate) "/full.jpg")
        data (.getBytes "bytes")]
    (testing "missing objects fetch as nil"
      (is (nil? (blob/fetch store key))))
    (blob/put! store key data "image/jpeg")
    (testing "what was put comes back with its type and length"
      (let [object (blob/fetch store key)]
        (is (= "image/jpeg" (:content-type object)))
        (is (= (alength ^bytes data) (:content-length object)))
        (is (= (seq data) (seq ^bytes (read-all object))))))
    (blob/delete! store key)
    (testing "deleted objects are gone; deleting again is fine"
      (is (nil? (blob/fetch store key)))
      (blob/delete! store key))))
