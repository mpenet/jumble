(ns s-exp.jumble.irc.protocol-test
  "IRC lines read and written: what the server says, the commands the bot
  sends, and text made safe to send."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.irc.protocol :as irc])
  (:import (java.nio.charset StandardCharsets)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(deftest parse-test
  (testing "a server's line: its source, command and parameters, the last one trailing"
    (is (= {:source "ann!a@host" :command "PRIVMSG" :params ["#swap" "hello there"]}
           (irc/parse ":ann!a@host PRIVMSG #swap :hello there")))
    (is (= {:source nil :command "PING" :params ["irc.example.org"]} (irc/parse "PING :irc.example.org")))
    (is (= {:source "irc.example.org" :command "001" :params ["jumble" "Welcome"]}
           (irc/parse ":irc.example.org 001 jumble :Welcome")))
    (is (= {:source nil :command "CAP" :params ["*" "ACK" "sasl"]} (irc/parse "CAP * ACK :sasl"))))
  (testing "tags ignored, nothing blank"
    (is (= "PRIVMSG" (:command (irc/parse "@time=2026-01-01T00:00:00Z :ann!a@h PRIVMSG #swap :hi"))))
    (is (nil? (irc/parse "")))))

(deftest line-test
  (testing "commands as written to the server"
    (is (= "JOIN #swap" (irc/line "JOIN" "#swap")))
    (is (= "JOIN #swap door" (irc/line "JOIN" "#swap" "door")))
    (is (= "PRIVMSG #swap :New on Swap Shop: lamp" (irc/line "PRIVMSG" "#swap" "New on Swap Shop: lamp")))
    (is (= "USER jumble 0 * :Jumble bot" (irc/line "USER" "jumble" "0" "*" "Jumble bot")))))

(deftest safe-text-test
  (testing "no line break, nor any control character, can end the line and start a command of its own"
    (let [text (irc/safe-text "lamp\r\nQUIT :bye\u0000\u0001ACTION x\u0001")]
      (is (not-any? #(< (int %) 32) text))
      (is (= "lamp QUIT :bye ACTION x" text))))
  (testing "cut to fit a line, never inside a character"
    (let [text (irc/safe-text (apply str (repeat 300 "é")))]
      (is (<= (alength (.getBytes ^String text StandardCharsets/UTF_8)) irc/max-text-bytes))
      (is (str/ends-with? text "…"))
      (is (every? #{\é \…} text)))))

(deftest fit-test
  (testing "cut to a number of bytes on whole characters, an emoji never halved"
    (let [cut (irc/fit (apply str (repeat 50 "😀")) 21)]
      (is (<= (alength (.getBytes ^String cut StandardCharsets/UTF_8)) 21))
      (is (= (str (apply str (repeat 4 "😀")) "…") cut))))
  (testing "what fits is left as it is"
    (is (= "lamp" (irc/fit "lamp" 10)))))

(deftest sasl-test
  (testing "SASL PLAIN: the account and password, in base64"
    (is (= ["AUTHENTICATE anVtYmxlAGp1bWJsZQBzM2NyZXQ="]
           (irc/sasl-plain "jumble" "s3cret"))))
  (testing "in chunks of 400, a full last one followed by +"
    (let [lines (irc/sasl-plain "j" (apply str (repeat 296 "x")))
          payload (str "j\u0000j\u0000" (apply str (repeat 296 "x")))]
      (is (= 2 (count lines)))
      (is (= "AUTHENTICATE +" (last lines)))
      (is (= (.encodeToString (Base64/getEncoder) (.getBytes ^String payload StandardCharsets/UTF_8))
             (subs (first lines) (count "AUTHENTICATE ")))))))
