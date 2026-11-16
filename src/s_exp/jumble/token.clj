(ns s-exp.jumble.token
  "Random tokens for sessions and links, the digests stored in their
  place, and comparing secrets."
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.util Base64 HexFormat)))

(set! *warn-on-reflection* true)

(def ^:private random
  "Seeded on first use, at run time: never while compiling, where a native
  image would freeze its state into the binary."
  (delay (SecureRandom.)))

(defn base64url
  "`bs` encoded as base64url without padding."
  ^String [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn generate
  "Random bytes (32 unless `n` is given) encoded as base64url without padding."
  (^String [] (generate 32))
  (^String [n]
   (let [bytes (byte-array n)]
     (.nextBytes ^SecureRandom @random bytes)
     (base64url bytes))))

(defn sha256
  "SHA-256 of `bs`."
  ^bytes [^bytes bs]
  (.digest (MessageDigest/getInstance "SHA-256") bs))

(defn sha256-hex
  "SHA-256 of `bs`, in lowercase hex."
  ^String [^bytes bs]
  (.formatHex (HexFormat/of) (sha256 bs)))

(defn digest
  "SHA-256 of `s` (UTF-8). Tokens are high-entropy, so an unsalted fast hash
  is enough to make a leaked table useless."
  ^bytes [^String s]
  (sha256 (.getBytes s StandardCharsets/UTF_8)))

(defn same?
  "Whether strings `a` and `b` are equal, false when either is nil; compared
  in a time that does not tell how much of a secret was guessed right."
  [^String a ^String b]
  (boolean (and a
                b
                (MessageDigest/isEqual (.getBytes a StandardCharsets/UTF_8)
                                       (.getBytes b StandardCharsets/UTF_8)))))
