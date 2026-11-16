(ns s-exp.jumble.irc.protocol
  "IRC lines read and written (RFC 1459 and its modern use): what the
  server says, the commands the bot sends, and text made safe to send."
  (:require [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(def max-text-bytes
  "Bytes of text in a message: a line is 512 bytes at most, and the server
  prefixes what it relays with the bot's nick, user and host."
  400)

(defn parse
  "`{:source :command :params}` of `line` as the server sent it, without
  its line ending; message tags are dropped. Nil for a blank line."
  [^String line]
  (let [line (if (str/starts-with? line "@") (or (second (str/split line #" " 2)) "") line)
        [source line] (if (str/starts-with? line ":")
                        (let [[s more] (str/split line #" " 2)] [(subs s 1) (or more "")])
                        [nil line])
        [head trailing] (str/split line #" :" 2)
        [command & middle] (remove str/blank? (str/split head #" +"))]
    (when command
      {:source source
       :command (str/upper-case command)
       :params (cond-> (vec middle) trailing (conj trailing))})))

(defn line
  "The line sending `command` with `params`, the last one written as a
  trailing parameter when it may hold spaces."
  [command & params]
  (let [[middle last-param] [(butlast params) (last params)]]
    (str/join " "
              (concat [command]
                      middle
                      (when last-param
                        [(if (or (str/includes? last-param " ")
                                 (str/starts-with? last-param ":")
                                 (empty? last-param))
                           (str ":" last-param)
                           last-param)])))))

(defn- byte-count
  [^String s]
  (alength (.getBytes s StandardCharsets/UTF_8)))

(defn fit
  "`s` cut to `max-bytes` of UTF-8, with an ellipsis, on whole characters:
  never inside one, nor between the halves of an emoji."
  [^String s max-bytes]
  (if (<= (byte-count s) max-bytes)
    s
    (loop [n (min (.codePointCount s 0 (count s)) max-bytes)]
      (let [cut (str (subs s 0 (.offsetByCodePoints s 0 (int n))) "…")]
        (if (or (<= (byte-count cut) max-bytes) (zero? n))
          cut
          (recur (dec n)))))))

(defn safe-text
  "`s` sendable as a message's text: control characters, line breaks among
  them, which would end the line and start a command of the sender's
  choosing, become spaces; then it is cut to [[max-text-bytes]]."
  [^String s]
  (-> s
      (str/replace #"[\x00-\x1f\x7f]+" " ")
      (str/replace #" {2,}" " ")
      str/trim
      (fit max-text-bytes)))

(defn sasl-plain
  "The AUTHENTICATE lines carrying `account`'s `password` (SASL PLAIN), in
  base64 chunks of 400, a last full one followed by +."
  [account password]
  (let [encoded (.encodeToString (Base64/getEncoder)
                                 (.getBytes (str account "\u0000" account "\u0000" password) StandardCharsets/UTF_8))
        chunks (map #(apply str %) (partition-all 400 encoded))]
    (mapv #(str "AUTHENTICATE " %)
          (cond-> (vec chunks) (zero? (mod (count encoded) 400)) (conj "+")))))
