(ns s-exp.jumble.store.s3
  "Minimal S3 client: put, get and delete of whole objects with path-style
  URLs, signed with AWS Signature Version 4 (header-based, signed payload)."
  (:require [clojure.string :as str]
            [s-exp.ex :as ex]
            [s-exp.jumble.error :as error]
            [s-exp.jumble.http :as http]
            [s-exp.jumble.token :as token])
  (:import (java.io InputStream)
           (java.net URI)
           (java.nio.charset StandardCharsets)
           (javax.crypto Mac)
           (java.time Instant ZoneOffset ZonedDateTime)
           (java.time.temporal ChronoUnit)
           (java.time.format DateTimeFormatter)
           (javax.crypto.spec SecretKeySpec)))

(set! *warn-on-reflection* true)

(defn- hex
  ^String [^bytes bs]
  (let [sb (StringBuilder. (* 2 (alength bs)))]
    (doseq [b bs]
      (.append sb (format "%02x" (bit-and (long b) 0xff))))
    (.toString sb)))

(defn- utf8
  ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- hmac
  ^bytes [^bytes key ^String data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. key "HmacSHA256"))
    (.doFinal mac (utf8 data))))

(defn- unreserved?
  "RFC 3986 unreserved: ASCII letters, digits, - . _ ~"
  [b]
  (or (<= 0x41 b 0x5a) (<= 0x61 b 0x7a) (<= 0x30 b 0x39) (contains? #{0x2d 0x2e 0x5f 0x7e} b)))

(defn- encode-segment
  "UTF-8 bytes of `segment`, percent-encoded except unreserved characters."
  [^String segment]
  (let [sb (StringBuilder.)]
    (doseq [b (utf8 segment)]
      (let [b (bit-and (long b) 0xff)]
        (if (unreserved? b)
          (.append sb (char b))
          (.append sb (format "%%%02X" b)))))
    (.toString sb)))

(defn canonical-path
  "`path` with each segment URI-encoded once, as S3 expects."
  [path]
  (str/join "/" (map encode-segment (str/split path #"/" -1))))

(defn- canonical-headers
  "Lowercased names, trimmed values with inner whitespace runs collapsed,
  sorted by name."
  [headers]
  (sort-by first (map (fn [[k v]] [(str/lower-case k) (str/replace (str/trim (str v)) #"\s+" " ")])
                      headers)))

(defn- sign
  "Signature parts for a request: `query` is the canonical query string,
  `headers` the headers to sign (name -> value)."
  [{:keys [method path query headers payload-hash amz-date]} {:keys [secret-key region service]}]
  (let [headers (canonical-headers headers)
        date (subs amz-date 0 8)
        signed-headers (str/join ";" (map first headers))
        canonical-request (str/join "\n" [(str/upper-case (name method))
                                          (canonical-path path)
                                          query
                                          (apply str (map (fn [[k v]] (str k ":" v "\n")) headers))
                                          signed-headers
                                          payload-hash])
        scope (str/join "/" [date region service "aws4_request"])
        string-to-sign (str/join "\n" ["AWS4-HMAC-SHA256" amz-date scope
                                       (token/sha256-hex (utf8 canonical-request))])
        signing-key (-> (utf8 (str "AWS4" secret-key))
                        (hmac date)
                        (hmac region)
                        (hmac service)
                        (hmac "aws4_request"))]
    {:scope scope
     :signed-headers signed-headers
     :signature (hex (hmac signing-key string-to-sign))}))

(defn authorization
  "Value of the Authorization header for `request` (`:method`, `:path`
  without query string, `:headers` including host and x-amz-date, and the
  hex SHA-256 `:payload-hash`), signing every header given."
  [{:keys! [method path headers payload-hash]} {:keys! [access-key] :as credentials}]
  (let [amz-date (some (fn [[k v]] (when (= "x-amz-date" (str/lower-case k)) v)) headers)
        {:keys [scope signed-headers signature]}
        (sign {:method method :path path :query "" :headers headers
               :payload-hash payload-hash :amz-date amz-date}
              credentials)]
    (str "AWS4-HMAC-SHA256 Credential=" access-key "/" scope
         ", SignedHeaders=" signed-headers
         ", Signature=" signature)))

(defn- query-string
  "Canonical query string: RFC 3986 encoded, sorted by name."
  [params]
  (str/join "&" (map (fn [[k v]] (str (encode-segment k) "=" (encode-segment v)))
                     (sort-by first params))))

(defn presigned-query
  "Query string authorizing a GET of `path` on `host` for `expires` seconds
  from `amz-date`, without credentials in the request."
  [{:keys! [host path amz-date expires]} {:keys! [access-key region service] :as credentials}]
  (let [scope (str/join "/" [(subs amz-date 0 8) region service "aws4_request"])
        params {"X-Amz-Algorithm" "AWS4-HMAC-SHA256"
                "X-Amz-Credential" (str access-key "/" scope)
                "X-Amz-Date" amz-date
                "X-Amz-Expires" (str expires)
                "X-Amz-SignedHeaders" "host"}
        {:keys [signature]} (sign {:method :get :path path :query (query-string params)
                                   :headers {"host" host} :payload-hash "UNSIGNED-PAYLOAD"
                                   :amz-date amz-date}
                                  credentials)]
    (str (query-string params) "&X-Amz-Signature=" signature)))

(defn client
  "Client for `bucket` at `endpoint` (scheme, host and port), path-style."
  [{:keys! [endpoint region bucket access-key secret-key]}]
  {:endpoint endpoint
   :host (.getRawAuthority (URI. endpoint))
   :bucket bucket
   :credentials {:access-key access-key :secret-key secret-key :region region :service "s3"}
   :http (http/client {:connect-timeout 5000 :timeout 30000})})

(def ^:private amz-date-format
  (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'"))

(defn- send!
  "Signed request for object `key`, also sending the (unsigned) `object-headers`
  such as Content-Type. The response body is a stream the caller must consume
  or close."
  [{:keys [endpoint host bucket credentials http]} method key ^bytes body object-headers]
  (let [path (str "/" bucket "/" key)
        payload-hash (token/sha256-hex (or body (byte-array 0)))
        headers {"host" host
                 "x-amz-date" (.format ^DateTimeFormatter amz-date-format (ZonedDateTime/now ZoneOffset/UTC))
                 "x-amz-content-sha256" payload-hash}
        authorization (authorization {:method method :path path :headers headers :payload-hash payload-hash}
                                     credentials)]
    (http/request http {:method method
                        :url (str endpoint (canonical-path path))
                        ;; The client sets Host itself, to the same value we signed.
                        :headers (merge object-headers
                                        (-> headers (dissoc "host") (assoc "authorization" authorization)))
                        :body body}
                  :stream)))

(defn- failure
  [operation key {:keys [status ^InputStream body]}]
  (let [detail (when body (with-open [in body] (slurp in)))]
    (ex/ex-info (str "S3 " operation " failed with " status) ::error/storage-failed
                {:operation operation :key key :status status :error detail})))

(defn- discard!
  "Closes the response body, releasing its connection."
  [{:keys [^InputStream body]}]
  (.close body))

(defn put!
  "Stores `data` under `key`. `object-headers` (e.g. Content-Type,
  Cache-Control) are kept with the object and returned when it is fetched."
  [client key ^bytes data object-headers]
  (let [response (send! client :put key data object-headers)]
    (when-not (= 200 (:status response))
      (throw (failure "put" key response)))
    (discard! response)))

(defn fetch
  "`{:body InputStream :content-type :content-length}` for object `key`, or
  nil when it does not exist. The caller must close the body."
  [client key]
  (let [{:keys [status headers ^InputStream body] :as response} (send! client :get key nil nil)]
    (case (long status)
      200 {:body body
           :content-type (get headers "content-type")
           :content-length (some-> (get headers "content-length") parse-long)}
      404 (do (.close body) nil)
      (throw (failure "get" key response)))))

(defn delete!
  "Deletes object `key`; deleting a missing object succeeds."
  [client key]
  (let [response (send! client :delete key nil nil)]
    (when-not (#{200 204 404} (:status response))
      (throw (failure "delete" key response)))
    (discard! response)))

(def ^:private presign-validity
  "Seconds a presigned URL is valid from the start of its UTC day: always at
  least a day from when it is handed out."
  (* 48 60 60))

(defn presign-url
  "URL granting a GET of object `key` to whoever holds it. Signed as of the
  start of `now`'s UTC day, so it is the same URL all day long and browsers
  can cache what it points to."
  [{:keys [endpoint host bucket credentials]} key ^Instant now]
  (let [day (.truncatedTo (.atZone now ZoneOffset/UTC) ChronoUnit/DAYS)
        path (str "/" bucket "/" key)]
    (str endpoint (canonical-path path) "?"
         (presigned-query {:host host
                           :path path
                           :amz-date (.format ^DateTimeFormatter amz-date-format day)
                           :expires presign-validity}
                          credentials))))
