(ns s-exp.jumble.push.crypto-test
  "Push messages encrypted as browsers decrypt them (RFC 8291), and the
  requests carrying them signed for the push services (RFC 8292)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.push.crypto :as crypto])
  (:import (com.nimbusds.jose.crypto ECDSAVerifier)
           (com.nimbusds.jwt SignedJWT)
           (java.nio ByteBuffer)
           (java.security KeyPairGenerator SecureRandom)
           (java.security.interfaces ECPublicKey)
           (java.security.spec ECGenParameterSpec)
           (java.util Arrays Base64)
           (javax.crypto Cipher KeyAgreement Mac)
           (javax.crypto.spec GCMParameterSpec SecretKeySpec)))

(set! *warn-on-reflection* true)

(defn- b64 ^String [^bytes bs] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))
(defn- unb64 ^bytes [^String s] (.decode (Base64/getUrlDecoder) s))

(defn- hmac ^bytes [^bytes k ^bytes data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. k "HmacSHA256"))
    (.doFinal mac data)))

(defn- cat-bytes ^bytes [& arrays]
  (let [out (java.io.ByteArrayOutputStream.)]
    (doseq [^bytes a arrays] (.write out a))
    (.toByteArray out)))

(defn- browser
  "A browser's subscription keys: its P-256 key pair and auth secret."
  []
  (let [g (doto (KeyPairGenerator/getInstance "EC") (.initialize (ECGenParameterSpec. "secp256r1")))
        kp (.generateKeyPair g)
        auth (byte-array 16)]
    (.nextBytes (SecureRandom.) auth)
    {:pair kp :auth auth}))

(defn- decrypt
  "What a browser does with a push message (RFC 8291, section 3)."
  [{:keys [^java.security.KeyPair pair ^bytes auth]} ^bytes message]
  (let [buf (ByteBuffer/wrap message)
        salt (byte-array 16)
        _ (.get buf salt)
        rs (.getInt buf)
        idlen (bit-and 0xff (.get buf))
        keyid (byte-array idlen)
        _ (.get buf keyid)
        ciphertext (Arrays/copyOfRange message (.position buf) (alength message))
        ua-public (crypto/public-bytes (.getPublic pair))
        ka (doto (KeyAgreement/getInstance "ECDH")
             (.init (.getPrivate pair))
             (.doPhase (crypto/public-key keyid) true))
        ecdh (.generateSecret ka)
        prk-key (hmac auth ecdh)
        ikm (hmac prk-key (cat-bytes (.getBytes "WebPush: info\u0000" "US-ASCII") ua-public keyid (byte-array [1])))
        prk (hmac salt ikm)
        cek (Arrays/copyOf (hmac prk (.getBytes "Content-Encoding: aes128gcm\u0000\u0001" "US-ASCII")) 16)
        nonce (Arrays/copyOf (hmac prk (.getBytes "Content-Encoding: nonce\u0000\u0001" "US-ASCII")) 12)
        cipher (doto (Cipher/getInstance "AES/GCM/NoPadding")
                 (.init Cipher/DECRYPT_MODE (SecretKeySpec. cek "AES") (GCMParameterSpec. 128 nonce)))
        padded (.doFinal cipher ciphertext)
        end (int (loop [i (dec (alength padded))] (if (zero? (aget padded i)) (recur (dec i)) i)))]
    {:rs rs :idlen idlen :delimiter (aget padded end)
     :text (String. (Arrays/copyOf padded end) "UTF-8")}))

(deftest encrypt-test
  (let [b (browser)
        subscription {:p256dh (b64 (crypto/public-bytes (.getPublic ^java.security.KeyPair (:pair b)))) :auth (b64 (:auth b))}
        message (crypto/encrypt subscription (.getBytes "{\"title\":\"Hello\"}" "UTF-8"))]
    (testing "a browser holding the subscription's keys reads it, a single record"
      (is (= {:rs 4096 :idlen 65 :delimiter 2 :text "{\"title\":\"Hello\"}"} (decrypt b message))))
    (testing "each message under new keys"
      (is (not= (seq message) (seq (crypto/encrypt subscription (.getBytes "{\"title\":\"Hello\"}" "UTF-8"))))))))

(deftest keys-test
  (testing "keys survive being stored as text"
    (let [k (crypto/generate-keys)
          again (crypto/read-keys (crypto/write-keys k))]
      (is (= (seq (crypto/public-bytes (:public k))) (seq (crypto/public-bytes (:public again)))))
      (is (= 65 (alength (crypto/public-bytes (:public k)))))
      (is (= (crypto/application-key k) (b64 (crypto/public-bytes (:public k))))
          "the key browsers subscribe with"))))

(deftest vapid-test
  (let [k (crypto/generate-keys)
        header (crypto/authorization k "https://fcm.googleapis.com/fcm/send/abc123" "https://market.example.org" 1700000000000)
        [_ ^String t key] (re-matches #"vapid t=([^,]+), k=(.+)" header)
        jwt (SignedJWT/parse t)
        claims (.getJWTClaimsSet jwt)]
    (testing "signed by the application's key, which it names"
      (is (= (crypto/application-key k) key))
      (is (.verify jwt (ECDSAVerifier. ^ECPublicKey (crypto/public-key (unb64 key))))))
    (testing "for the push service's origin, a while, from the community"
      (is (= ["https://fcm.googleapis.com"] (vec (.getAudience claims))))
      (is (= (+ 1700000000 (* 12 60 60)) (quot (.getTime (.getExpirationTime claims)) 1000)))
      (is (= "https://market.example.org" (.getSubject claims)))
      (is (str/starts-with? (str (.getAlgorithm (.getHeader jwt))) "ES256")))))
