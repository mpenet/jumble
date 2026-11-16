(ns s-exp.jumble.push.crypto
  "Push messages encrypted for the browser that subscribed (RFC 8291,
  aes128gcm), and the requests carrying them signed with the community's
  key (VAPID, RFC 8292), all with the JDK's cryptography."
  (:import (com.nimbusds.jose JOSEObjectType JWSAlgorithm JWSHeader$Builder)
           (com.nimbusds.jose.crypto ECDSASigner)
           (com.nimbusds.jwt JWTClaimsSet$Builder SignedJWT)
           (java.io ByteArrayOutputStream)
           (java.math BigInteger)
           (java.net URI)
           (java.nio ByteBuffer)
           (java.security AlgorithmParameters KeyFactory KeyPair KeyPairGenerator SecureRandom)
           (java.security.interfaces ECPrivateKey ECPublicKey)
           (java.security.spec ECGenParameterSpec ECParameterSpec ECPoint ECPublicKeySpec PKCS8EncodedKeySpec)
           (java.util Arrays Base64 Date)
           (javax.crypto Cipher KeyAgreement Mac)
           (javax.crypto.spec GCMParameterSpec SecretKeySpec)))

(set! *warn-on-reflection* true)

(def ^:private record-size
  "The size of the one record a message is: larger than any we send."
  4096)

(def ^:private validity
  "How long a signature on requests to a push service holds, in seconds: at
  most a day, by RFC 8292."
  (* 12 60 60))

(defn- b64 ^String [^bytes bs] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))
(defn- unb64 ^bytes [^String s] (.decode (Base64/getUrlDecoder) s))

(def ^:private ^ECParameterSpec p-256
  (let [params (AlgorithmParameters/getInstance "EC")]
    (.init params (ECGenParameterSpec. "secp256r1"))
    (.getParameterSpec params ECParameterSpec)))

(defn- key-pair
  ^KeyPair []
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "EC") (.initialize (ECGenParameterSpec. "secp256r1")))))

(defn- unsigned-32
  "`n` as 32 big-endian bytes."
  ^bytes [^BigInteger n]
  (let [bs (.toByteArray n)
        out (byte-array 32)
        len (min 32 (alength bs))]
    (System/arraycopy bs (- (alength bs) len) out (- 32 len) len)
    out))

(defn public-bytes
  "`k` as an uncompressed point, 65 bytes: as browsers give and take keys."
  ^bytes [^ECPublicKey k]
  (let [w (.getW k)
        out (ByteArrayOutputStream.)]
    (.write out 4)
    (.write out (unsigned-32 (.getAffineX w)))
    (.write out (unsigned-32 (.getAffineY w)))
    (.toByteArray out)))

(defn public-key
  "The P-256 public key of uncompressed point `bs`."
  ^ECPublicKey [^bytes bs]
  (let [x (BigInteger. 1 (Arrays/copyOfRange bs 1 33))
        y (BigInteger. 1 (Arrays/copyOfRange bs 33 65))]
    (.generatePublic (KeyFactory/getInstance "EC") (ECPublicKeySpec. (ECPoint. x y) p-256))))

(defn usable-key?
  "Whether `bs` is a P-256 public key keys can be agreed with: a point on
  the curve, as browsers give."
  [^bytes bs]
  (try
    (and (= 65 (alength bs))
         (= 4 (aget bs 0))
         (do (doto (KeyAgreement/getInstance "ECDH")
               (.init (.getPrivate (key-pair)))
               (.doPhase (public-key bs) true))
             true))
    (catch Exception _
      false)))

(defn generate-keys
  "A new key pair for the community to sign with."
  []
  (let [kp (key-pair)]
    {:private (.getPrivate kp) :public (.getPublic kp)}))

(defn write-keys
  "Keys as text, to be stored."
  [{:keys [^ECPrivateKey private public]}]
  {:private (b64 (.getEncoded private)) :public (b64 (public-bytes public))})

(defn read-keys
  "Keys stored with [[write-keys]]."
  [{:keys [private public]}]
  {:private (.generatePrivate (KeyFactory/getInstance "EC") (PKCS8EncodedKeySpec. (unb64 private)))
   :public (public-key (unb64 public))})

(defn application-key
  "The public key browsers subscribe with, as they take it."
  [{:keys [public]}]
  (b64 (public-bytes public)))

(defn- hmac
  ^bytes [^bytes k ^bytes data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. k "HmacSHA256"))
    (.doFinal mac data)))

(defn- concat-bytes
  ^bytes [& arrays]
  (let [out (ByteArrayOutputStream.)]
    (doseq [^bytes a arrays]
      (.write out a))
    (.toByteArray out)))

(defn- ascii ^bytes [^String s] (.getBytes s "US-ASCII"))

(defn encrypt
  "`plaintext` encrypted for the browser of `subscription` (its `:p256dh`
  key and `:auth` secret, as it gave them), under a key pair of its own:
  the body of a push message."
  ^bytes [{:keys [p256dh auth]} ^bytes plaintext]
  (let [ua-public (unb64 p256dh)
        kp (key-pair)
        as-public (public-bytes (.getPublic kp))
        ecdh (.generateSecret (doto (KeyAgreement/getInstance "ECDH")
                                (.init (.getPrivate kp))
                                (.doPhase (public-key ua-public) true)))
        ikm (hmac (hmac (unb64 auth) ecdh)
                  (concat-bytes (ascii "WebPush: info\u0000") ua-public as-public (byte-array [1])))
        salt (let [bs (byte-array 16)] (.nextBytes (SecureRandom.) bs) bs)
        prk (hmac salt ikm)
        cek (Arrays/copyOf (hmac prk (ascii "Content-Encoding: aes128gcm\u0000\u0001")) 16)
        nonce (Arrays/copyOf (hmac prk (ascii "Content-Encoding: nonce\u0000\u0001")) 12)
        cipher (doto (Cipher/getInstance "AES/GCM/NoPadding")
                 (.init Cipher/ENCRYPT_MODE (SecretKeySpec. cek "AES") (GCMParameterSpec. 128 nonce)))
        ;; The last record ends with 2.
        ciphertext (.doFinal cipher (concat-bytes plaintext (byte-array [2])))]
    (concat-bytes salt
                  (.array (.putInt (ByteBuffer/allocate 4) record-size))
                  (byte-array [(count as-public)])
                  as-public
                  ciphertext)))

(defn authorization
  "The Authorization header of a request to push `endpoint` at `now` (ms),
  signed with `keys`, from `subject` (the community's address)."
  [{:keys [^ECPrivateKey private] :as keys} endpoint subject now]
  (let [uri (URI. endpoint)
        claims (-> (JWTClaimsSet$Builder.)
                   (.audience (str (.getScheme uri) "://" (.getAuthority uri)))
                   (.expirationTime (Date. (long (* 1000 (+ (quot now 1000) validity)))))
                   (.subject subject)
                   (.build))
        jwt (SignedJWT. (-> (JWSHeader$Builder. JWSAlgorithm/ES256) (.type JOSEObjectType/JWT) (.build)) claims)]
    (.sign jwt (ECDSASigner. private))
    (str "vapid t=" (.serialize jwt) ", k=" (application-key keys))))
