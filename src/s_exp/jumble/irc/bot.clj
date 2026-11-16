(ns s-exp.jumble.irc.bot
  "A bot staying in the community's IRC channel to announce new posts:
  signing in (with SASL when it has a password), keeping its connection
  alive, getting back in when kicked or cut off. One virtual thread owns
  the connection; [[say!]] writes from any other."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [s-exp.jumble.irc.protocol :as irc])
  (:import (java.io BufferedReader InputStreamReader OutputStreamWriter Writer)
           (java.net InetSocketAddress Socket SocketTimeoutException)
           (java.nio.charset StandardCharsets)
           (javax.net.ssl SSLSocket SSLSocketFactory)))

(set! *warn-on-reflection* true)

(def ^:private defaults
  {;; How often the connection's thread looks up from reading.
   :tick-ms 1000
   ;; Silence after which the server is pinged, then after which the
   ;; connection is taken as dead.
   :ping-after-ms (* 2 60 1000)
   :dead-after-ms (* 4 60 1000)
   ;; Lines said at most one per, as networks throttle or ban floods.
   :line-interval-ms 2000
   ;; Wait before joining again, kicked or refused.
   :rejoin-after-ms (* 60 1000)
   ;; Waits between connection attempts, the last repeated.
   :reconnect-delays-ms [1000 2000 5000 15000 30000 60000 120000 300000]})

(defn- open
  "A socket to the server, over TLS with the JVM's checks of its certificate
  and name unless `tls` is false (tests)."
  ^Socket [{:keys [host port tls]}]
  (if tls
    (let [^SSLSocket socket (.createSocket ^SSLSocketFactory (SSLSocketFactory/getDefault))
          params (doto (.getSSLParameters socket) (.setEndpointIdentificationAlgorithm "HTTPS"))]
      (.setSSLParameters socket params)
      (.connect socket (InetSocketAddress. ^String host (int port)) 20000)
      (.startHandshake socket)
      socket)
    (doto (Socket.)
      (.connect (InetSocketAddress. ^String host (int port)) 20000))))

(defn- write!
  "Writes `line` on the current connection; false when there is none."
  [{:keys [state]} line]
  (if-let [^Writer out (:out @state)]
    (try
      (locking out
        (.write out (str line "\r\n"))
        (.flush out))
      true
      (catch java.io.IOException _ false))
    false))

(defn joined?
  "Whether the bot is in its channel, so that what it says is heard."
  [{:keys [state]}]
  (boolean (:joined @state)))

(defn say!
  "Says `text` in the channel, made safe to send, at most one line per
  interval; true once written, false when the bot is not in the channel."
  [{:keys [state options config] :as bot} text]
  (and (joined? bot)
       (locking state
         (let [wait (- (+ (:said-at @state 0) (:line-interval-ms options)) (System/currentTimeMillis))]
           (when (pos? wait) (Thread/sleep (long wait)))
           (let [written (write! bot (irc/line "PRIVMSG" (:channel config) (irc/safe-text text)))]
             (swap! state assoc :said-at (System/currentTimeMillis))
             written)))))

(defn- join!
  [{:keys [config] :as bot}]
  (let [{:keys [channel key]} config]
    (write! bot (if key (irc/line "JOIN" channel key) (irc/line "JOIN" channel)))))

(defn- own-nick?
  [{:keys [state]} source]
  (= (:nick @state) (first (str/split (or source "") #"!" 2))))

(defn- handle!
  "Acts on one line from the server."
  [{:keys [state config options] :as bot} {:keys [source command params]}]
  (let [{:keys [nick password channel]} config
        now (System/currentTimeMillis)]
    (case command
      "PING" (write! bot (apply irc/line "PONG" params))
      "CAP" (when (= "sasl" (str/trim (last params)))
              (case (second params)
                "ACK" (write! bot "AUTHENTICATE PLAIN")
                "NAK" (do (log/warn "IRC server does not offer SASL; going on without signing in")
                          (write! bot "CAP END"))
                nil))
      "AUTHENTICATE" (when (= ["+"] params)
                       (doseq [line (irc/sasl-plain nick password)] (write! bot line)))
      "903" (write! bot "CAP END")
      ("902" "904" "905" "906") (do (log/warn "IRC SASL sign-in failed, going on without" {:numeric command})
                                    (write! bot "CAP END"))
      "001" (do (swap! state assoc :registered true :attempt 0)
                (join! bot))
      "433" (when-not (:registered @state)
              (let [taken (swap! state update :nick str "_")]
                (write! bot (irc/line "NICK" (:nick taken)))))
      "JOIN" (when (and (own-nick? bot source) (= (str/lower-case channel) (str/lower-case (first params))))
               (log/info "IRC bot joined its channel")
               (swap! state assoc :joined true :rejoin-at nil))
      "KICK" (when (= (:nick @state) (second params))
               (log/warn "IRC bot kicked from its channel; joining again in a while")
               (swap! state assoc :joined false :rejoin-at (+ now (:rejoin-after-ms options))))
      ;; Banned, invite only, full, wrong key: tried again in a while.
      ("471" "473" "474" "475") (do (log/warn "IRC bot could not join its channel" {:numeric command})
                                    (swap! state assoc :rejoin-at (+ now (:rejoin-after-ms options))))
      "ERROR" (throw (java.io.IOException. "The IRC server closed the connection"))
      nil)))

(defn- tick!
  "Between lines: pings a silent server, gives up on a dead connection,
  joins again when due."
  [{:keys [state options] :as bot}]
  (let [{:keys [heard-at pinged rejoin-at]} @state
        now (System/currentTimeMillis)
        silent (- now heard-at)]
    (cond
      (>= silent (:dead-after-ms options)) (throw (java.io.IOException. "The IRC server stopped answering"))
      (and (>= silent (:ping-after-ms options)) (not pinged)) (do (write! bot (irc/line "PING" "jumble"))
                                                                  (swap! state assoc :pinged true))
      (and rejoin-at (>= now rejoin-at)) (do (swap! state assoc :rejoin-at nil)
                                             (join! bot)))))

(defn- session!
  "One connection, from signing in until it ends."
  [{:keys [state config options] :as bot}]
  (with-open [socket (open config)]
    (.setSoTimeout socket (int (:tick-ms options)))
    (let [reader (BufferedReader. (InputStreamReader. (.getInputStream socket) StandardCharsets/UTF_8))
          out (OutputStreamWriter. (.getOutputStream socket) StandardCharsets/UTF_8)
          {:keys [nick password]} config]
      (swap! state assoc :out out :socket socket :nick nick :registered false :joined false
             :heard-at (System/currentTimeMillis) :pinged false :rejoin-at nil)
      (when password (write! bot "CAP REQ :sasl"))
      (write! bot (irc/line "NICK" nick))
      (write! bot (irc/line "USER" nick "0" "*" "Jumble"))
      (loop []
        (when (:running @state)
          (let [line (try (.readLine reader) (catch SocketTimeoutException _ ::quiet))]
            (cond
              (nil? line) (throw (java.io.IOException. "The IRC server closed the connection"))
              (= ::quiet line) (tick! bot)
              :else (do (swap! state assoc :heard-at (System/currentTimeMillis) :pinged false)
                        (some->> (irc/parse line) (handle! bot))
                        (tick! bot))))
          (recur))))))

(defn- keep-connected!
  [{:keys [state options] :as bot}]
  (while (:running @state)
    (try
      (session! bot)
      (catch Exception e
        (when (:running @state)
          (log/warn "IRC connection lost" {:reason (.getMessage e)}))))
    (swap! state assoc :out nil :socket nil :joined false)
    (when (:running @state)
      (let [delays (:reconnect-delays-ms options)
            attempt (:attempt (swap! state update :attempt (fnil inc 0)))]
        (Thread/sleep (long (nth delays (min (dec attempt) (dec (count delays))))))))))

(defn start
  "Starts a bot for `config`, `{:host :port :tls :nick :password :channel
  :key}`, with `options` overriding the timings in [[defaults]] (tests)."
  ([config] (start config {}))
  ([config options]
   (let [bot {:config config
              :options (merge defaults options)
              :state (atom {:running true :attempt 0})}]
     (assoc bot :thread (Thread/startVirtualThread #(keep-connected! bot))))))

(defn stop
  "Says goodbye and closes the connection, for good."
  [{:keys [state ^Thread thread] :as bot}]
  (swap! state assoc :running false)
  (write! bot (irc/line "QUIT" "Bye"))
  (when-let [^Socket socket (:socket @state)]
    (.close socket))
  (.join thread 5000))
