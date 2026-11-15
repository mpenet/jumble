(ns s-exp.jumble.store.blob
  "Object storage for photos: S3-compatible in production, in memory in
  tests. Browsers fetch objects straight from the store through [[url]]."
  (:require [s-exp.jumble.store.s3 :as s3])
  (:import (java.io ByteArrayInputStream)))

(set! *warn-on-reflection* true)

(defprotocol Store
  (put! [store key data content-type]
    "Stores the bytes `data` under `key`.")
  (fetch [store key]
    "`{:body InputStream :content-type :content-length}` for `key`, or nil.
    The caller must close the body.")
  (delete! [store key]
    "Removes `key`; removing a missing key succeeds.")
  (url [store key now]
    "URL a browser can GET `key` from, valid at least a day after `now`
    (an Instant). Stable through `now`'s UTC day, so browsers can cache."))

(defrecord S3Store [client]
  Store
  (put! [_ key data content-type]
    (s3/put! client key data {"content-type" content-type
                              ;; Keys are never reused, so objects never change.
                              "cache-control" "private, max-age=172800, immutable"}))
  (fetch [_ key] (s3/fetch client key))
  (delete! [_ key] (s3/delete! client key))
  (url [_ key now] (s3/presign-url client key now)))

(defn s3-store
  [config]
  (->S3Store (s3/client config)))

(defrecord MemoryStore [objects]
  Store
  (put! [_ key data content-type]
    (swap! objects assoc key {:data data :content-type content-type}))
  (fetch [_ key]
    (when-let [{:keys [^bytes data content-type]} (get @objects key)]
      {:body (ByteArrayInputStream. data)
       :content-type content-type
       :content-length (alength data)}))
  (delete! [_ key]
    (swap! objects dissoc key))
  (url [_ key _now]
    (str "https://blobs.test/" key)))

(defn memory-store
  []
  (->MemoryStore (atom {})))
