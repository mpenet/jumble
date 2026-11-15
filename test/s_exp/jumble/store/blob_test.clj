(ns s-exp.jumble.store.blob-test
  "The in-memory object store tests use."
  (:require [clojure.test :refer [deftest is]]
            [s-exp.jumble.store.blob :as blob]
            [s-exp.jumble.store.blob-contract :as contract]))

(deftest memory-store-test
  (contract/check (blob/memory-store)))

(deftest memory-store-url-test
  (is (= "https://blobs.test/photos/a/full.jpg"
         (blob/url (blob/memory-store) "photos/a/full.jpg" (java.time.Instant/now)))))
