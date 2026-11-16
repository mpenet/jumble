(ns s-exp.jumble.irc.bot-test
  "The bot staying in the community's IRC channel, against a server of the
  test's own: signing in, SASL, keeping the connection, getting back in."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.irc.bot :as bot]
            [s-exp.jumble.metrics :as metrics])
  (:import (java.io BufferedReader InputStreamReader OutputStreamWriter Writer)
           (java.net ServerSocket Socket)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(set! *warn-on-reflection* true)

(defn- server
  "An IRC server on a free local port: `:said` holds the lines the bot
  sent, `:connections` each connection it made."
  []
  (let [listener (ServerSocket. 0)
        said (LinkedBlockingQueue.)
        connections (LinkedBlockingQueue.)]
    (Thread/startVirtualThread
     (fn []
       (try
         (loop []
           (let [socket (.accept listener)
                 reader (BufferedReader. (InputStreamReader. (.getInputStream socket) StandardCharsets/UTF_8))]
             (.put connections socket)
             (Thread/startVirtualThread
              (fn []
                (try
                  (loop []
                    (when-let [line (.readLine reader)]
                      (.put said line)
                      (recur)))
                  (catch java.io.IOException _))))
             (recur)))
         (catch java.io.IOException _))))
    {:listener listener :said said :connections connections}))

(defn- next-said
  "The next line the bot sent, waiting up to 3 s."
  [{:keys [^LinkedBlockingQueue said]}]
  (.poll said 3 TimeUnit/SECONDS))

(defn- said-until
  "Lines the bot sent, until one matching `re`, which is returned."
  [srv re]
  (loop [n 0]
    (when (< n 50)
      (when-let [line (next-said srv)]
        (if (re-find re line) line (recur (inc n)))))))

(defn- connection
  "The next connection the bot made, waiting up to 3 s."
  ^Socket [{:keys [^LinkedBlockingQueue connections]}]
  (.poll connections 3 TimeUnit/SECONDS))

(defn- tell!
  "The server writes `line` to the bot on `socket`."
  [^Socket socket line]
  (let [out (OutputStreamWriter. (.getOutputStream socket) StandardCharsets/UTF_8)]
    (.write ^Writer out (str line "\r\n"))
    (.flush out)))

(defn- start
  [srv & {:as options}]
  (bot/start (merge {:host "127.0.0.1" :port (.getLocalPort ^ServerSocket (:listener srv)) :tls false
                     :nick "jumble" :password nil :channel "#swap" :key nil}
                    options)
             {:tick-ms 50 :ping-after-ms 400 :dead-after-ms 1200 :line-interval-ms 100 :rejoin-after-ms 200
              :reconnect-delays-ms [50 100]}))

(defn- eventually
  [f]
  (loop [n 100] (or (f) (when (pos? n) (Thread/sleep 30) (recur (dec n))))))

(defn- joined!
  "Signs the bot in on `socket` and has it join #swap."
  [srv socket]
  (is (said-until srv #"^USER "))
  (tell! socket ":irc.test 001 jumble :Welcome")
  (is (= "JOIN #swap" (said-until srv #"^JOIN ")))
  (tell! socket ":jumble!j@host JOIN #swap"))

(deftest join-and-say-test
  (let [srv (server)
        b (start srv)]
    (try
      (let [socket (connection srv)]
        (testing "it signs in and joins the channel once welcomed"
          (is (= "NICK jumble" (said-until srv #"^NICK ")))
          (is (not (bot/joined? b)))
          (is (not (bot/say! b "too early")) "nothing said before it is in")
          (joined! srv socket)
          (is (eventually #(bot/joined? b)))
          (is (str/includes? (metrics/text) "jumble_irc_joined 1") "watched by the dashboard"))
        (testing "and says what it is given, made safe: no line break starts a command"
          (is (bot/say! b "Desk lamp\r\nQUIT :bye"))
          (is (= "PRIVMSG #swap :Desk lamp QUIT :bye" (said-until srv #"^PRIVMSG "))))
        (testing "one line at a time, spaced"
          (let [start (System/currentTimeMillis)]
            (bot/say! b "one")
            (bot/say! b "two")
            (is (>= (- (System/currentTimeMillis) start) 100))))
        (testing "it answers the server's pings"
          (tell! socket "PING :irc.test")
          (is (= "PONG irc.test" (said-until srv #"^PONG "))))
        (testing "a line arriving in pieces, slower than it looks up from reading, is read whole"
          (let [out (OutputStreamWriter. (.getOutputStream socket) StandardCharsets/UTF_8)]
            (.write ^Writer out "PING :sl")
            (.flush out)
            (Thread/sleep 200)
            (.write ^Writer out "ow-é\r\n")
            (.flush out))
          (is (= "PONG slow-é" (said-until srv #"^PONG ")))))
      (finally
        (bot/stop b)
        (.close ^ServerSocket (:listener srv))))))

(deftest sasl-test
  (let [srv (server)
        b (start srv :password "s3cret")]
    (try
      (let [socket (connection srv)]
        (testing "with a password, it signs in to its account with SASL first"
          (is (= "CAP REQ :sasl" (said-until srv #"^CAP ")))
          (tell! socket ":irc.test CAP * ACK :sasl")
          (is (= "AUTHENTICATE PLAIN" (said-until srv #"^AUTHENTICATE ")))
          (tell! socket "AUTHENTICATE +")
          (is (= "AUTHENTICATE anVtYmxlAGp1bWJsZQBzM2NyZXQ=" (said-until srv #"^AUTHENTICATE ")))
          (tell! socket ":irc.test 903 jumble :SASL authentication successful")
          (is (= "CAP END" (said-until srv #"^CAP ")))))
      (finally
        (bot/stop b)
        (.close ^ServerSocket (:listener srv))))))

(deftest nick-taken-test
  (let [srv (server)
        b (start srv)]
    (try
      (let [socket (connection srv)]
        (said-until srv #"^USER ")
        (testing "its nick taken, it tries another"
          (tell! socket ":irc.test 433 * jumble :Nickname is already in use")
          (is (= "NICK jumble_" (said-until srv #"^NICK ")))))
      (finally
        (bot/stop b)
        (.close ^ServerSocket (:listener srv))))))

(deftest kicked-test
  (let [srv (server)
        b (start srv)]
    (try
      (let [socket (connection srv)]
        (joined! srv socket)
        (eventually #(bot/joined? b))
        (testing "kicked, it is out, and joins again a while later"
          (tell! socket ":op!o@host KICK #swap jumble :out")
          (is (eventually #(not (bot/joined? b))))
          (is (str/includes? (metrics/text) "jumble_irc_joined 0"))
          (is (= "JOIN #swap" (said-until srv #"^JOIN ")))))
      (finally
        (bot/stop b)
        (.close ^ServerSocket (:listener srv))))))

(deftest reconnect-test
  (let [srv (server)
        b (start srv)]
    (try
      (let [socket (connection srv)]
        (joined! srv socket)
        (eventually #(bot/joined? b))
        (testing "the connection lost, it connects again and joins again"
          (.close socket)
          (is (eventually #(not (bot/joined? b))))
          (let [again (connection srv)]
            (is again)
            (joined! srv again)
            (is (eventually #(bot/joined? b))))))
      (finally
        (bot/stop b)
        (.close ^ServerSocket (:listener srv))))))

(deftest keepalive-test
  (let [srv (server)
        b (start srv)]
    (try
      (let [socket (connection srv)]
        (joined! srv socket)
        (eventually #(bot/joined? b))
        (testing "a silent server is pinged"
          (is (str/starts-with? (said-until srv #"^PING ") "PING ")))
        (testing "and, still silent, given up on for a new connection"
          (is (connection srv))))
      (finally
        (bot/stop b)
        (.close ^ServerSocket (:listener srv))))))

(deftest stop-test
  (let [srv (server)
        b (start srv)]
    (try
      (let [socket (connection srv)]
        (joined! srv socket)
        (eventually #(bot/joined? b))
        (testing "stopped, it says goodbye and does not come back"
          (bot/stop b)
          (is (str/starts-with? (said-until srv #"^QUIT") "QUIT"))
          (is (nil? (connection srv)))))
      (finally
        (.close ^ServerSocket (:listener srv))))))
