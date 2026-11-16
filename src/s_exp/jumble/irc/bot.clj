(ns s-exp.jumble.irc.bot
  "A bot staying in the community's IRC channel to announce new posts:
  signing in (with SASL when it has a password), keeping its connection
  alive, getting back in when kicked or cut off. One virtual thread owns
  the connection; [[say!]] writes from any other."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [s-exp.jumble.irc.protocol :as irc]
            [s-exp.jumble.metrics :as metrics])
  (:import (java.io BufferedInputStream ByteArrayOutputStream InputStream OutputStreamWriter Writer)
           (java.net InetSocketAddress Socket SocketTimeoutException)
           (java.nio.charset StandardCharsets)
           (javax.net.ssl SSLSocket SSLSocketFactory)))

(set! *warn-on-reflection* true)

(def ^:private joined-gauge
  (metrics/gauge "jumble_irc_joined" "Whether the IRC bot is in its channel, so new posts are announced: 1 or 0."))

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
   ;; A write taking longer, the server no longer reading, ends the
   ;; connection: it must not hold up the delivery loop saying something.
   :write-timeout-ms 10000
   ;; A connection lasting this long resets the waits between attempts.
   :stable-after-ms (* 60 1000)
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
      ;; Bounds the handshake too.
      (.setSoTimeout socket 20000)
      (.connect socket (InetSocketAddress. ^String host (int port)) 20000)
      (.startHandshake socket)
      socket)
    (doto (Socket.)
      (.setSoTimeout 20000)
      (.connect (InetSocketAddress. ^String host (int port)) 20000))))

(defn- write!
  "Writes `line` on the current connection; false when there is none, or it
  failed. A write under way is marked, for [[watch!]]."
  [{:keys [state]} line]
  (if-let [^Writer out (:out @state)]
    (try
      (locking out
        (swap! state assoc :writing-since (System/currentTimeMillis))
        (try
          (.write out (str line "\r\n"))
          (.flush out)
          (finally
            (swap! state dissoc :writing-since))))
      true
      (catch java.io.IOException _
        false))
    false))

(defn- watch!
  "Closes the connection when a write has been under way longer than the
  write timeout: the server stopped reading, and the writer, the delivery
  loop maybe, gets its failure instead of waiting for ever."
  [{:keys [state options]}]
  (while (:running @state)
    (try
      (Thread/sleep (long (:tick-ms options)))
      (let [{:keys [writing-since ^Socket socket]} @state]
        (when (and writing-since socket
                   (> (- (System/currentTimeMillis) writing-since) (:write-timeout-ms options)))
          (log/warn "IRC server stopped reading; connecting again")
          (.close socket)))
      (catch InterruptedException _))))

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
           ;; Still in, after waiting its turn: kicked meanwhile, it would
           ;; say it to nobody.
           (and (joined? bot)
                (let [written (write! bot (irc/line "PRIVMSG" (:channel config) (irc/safe-text text)))]
                  (swap! state assoc :said-at (System/currentTimeMillis))
                  written))))))

(defn- join!
  "Joins the channel, and tries again in a while unless the server says it
  did: however it refuses, if it says anything."
  [{:keys [config state options] :as bot}]
  (let [{:keys [channel key]} config]
    (swap! state assoc :rejoin-at (+ (System/currentTimeMillis) (:rejoin-after-ms options)))
    (write! bot (if key (irc/line "JOIN" channel key) (irc/line "JOIN" channel)))))

(defn- own-nick?
  [{:keys [state]} source]
  (= (:nick @state) (first (str/split (or source "") #"!" 2))))

(defn- own-channel?
  [{:keys [config]} channel]
  (and channel (= (str/lower-case (:channel config)) (str/lower-case channel))))

(def ^:private max-nick-tries
  "Nicks tried, each with one more _, before giving up the connection."
  3)

(defn- handle!
  "Acts on one line from the server."
  [{:keys [state config options] :as bot} {:keys [source command params]}]
  (let [{:keys [nick password]} config
        now (System/currentTimeMillis)
        out! #(do (log/warn %)
                  (swap! state assoc :joined false :rejoin-at (+ now (:rejoin-after-ms options))))]
    (case command
      "PING" (write! bot (apply irc/line "PONG" params))
      "CAP" (when (= "sasl" (some-> (last params) str/trim))
              (case (second params)
                "ACK" (write! bot "AUTHENTICATE PLAIN")
                "NAK" (do (log/warn "IRC server does not offer SASL; going on without signing in")
                          (write! bot "CAP END"))
                nil))
      "AUTHENTICATE" (when (= ["+"] params)
                       (doseq [line (irc/sasl-plain nick password)]
                         (write! bot line)))
      "903" (write! bot "CAP END")
      ("902" "904" "905" "906")
      (do (log/warn "IRC SASL sign-in failed, going on without" {:numeric command})
          (write! bot "CAP END"))
      ;; The nick the server gave, cut to its length maybe, is the bot's.
      "001" (do (swap! state assoc :registered true :nick (or (first params) (:nick @state)))
                (join! bot))
      "NICK" (when (and (own-nick? bot source) (first params))
               (swap! state assoc :nick (first params)))
      "433" (when-not (:registered @state)
              (let [taken (swap! state update :nick-tries (fnil inc 0))]
                (when (> (:nick-tries taken) max-nick-tries)
                  (throw (java.io.IOException. "The bot's nicks are all taken")))
                (write! bot (irc/line "NICK" (:nick (swap! state update :nick str "_"))))))
      "432" (throw (java.io.IOException. "The IRC server refuses the bot's nick"))
      "JOIN" (when (and (own-nick? bot source) (own-channel? bot (first params)))
               (log/info "IRC bot joined its channel")
               (swap! state assoc :joined true :rejoin-at nil))
      "KICK" (when (and (own-channel? bot (first params)) (= (:nick @state) (second params)))
               (out! "IRC bot kicked from its channel; joining again in a while"))
      "PART" (when (and (own-nick? bot source) (own-channel? bot (first params)))
               (out! "IRC bot made to leave its channel; joining again in a while"))
      ;; No such channel, too many, full, invite only, banned, wrong key,
      ;; registered nicks only...: tried again in a while (see join!).
      ("403" "405" "471" "473" "474" "475" "477" "489" "520")
      (log/warn "IRC bot could not join its channel" {:numeric command})
      "ERROR" (throw (java.io.IOException. "The IRC server closed the connection"))
      nil)))

(defn- tick!
  "Between lines: pings a silent server, gives up on a dead connection,
  joins again when due."
  [{:keys [state options] :as bot}]
  (let [{:keys [heard-at pinged rejoin-at joined]} @state
        now (System/currentTimeMillis)
        silent (- now heard-at)]
    (cond
      (>= silent (:dead-after-ms options))
      (throw (java.io.IOException. "The IRC server stopped answering"))

      (and (>= silent (:ping-after-ms options)) (not pinged))
      (do (write! bot (irc/line "PING" "jumble"))
          (swap! state assoc :pinged true))

      (and rejoin-at (not joined) (>= now rejoin-at))
      (join! bot))))

(def ^:private max-line-bytes
  "A longer line from the server is dropped: IRC's are 512 bytes, with
  message tags 8 KB more."
  (* 9 1024))

(defn- read-line!
  "The next line from `in`, decoded once whole, without its line ending;
  ::quiet when nothing came for a tick, the part of a line read so far kept
  in `pending` for the next call; nil when the server closed the
  connection."
  [^InputStream in ^ByteArrayOutputStream pending]
  (loop []
    (let [b (try
              (.read in)
              (catch SocketTimeoutException _
                ::quiet))]
      (cond
        (= ::quiet b) ::quiet
        (= -1 b) nil

        (= 10 b)
        (let [line (.toString pending StandardCharsets/UTF_8)]
          (.reset pending)
          (if (str/ends-with? line "\r")
            (subs line 0 (dec (count line)))
            line))

        (< (.size pending) max-line-bytes)
        (do (.write pending (int b))
            (recur))

        ;; Too long: skipped to its end, none of it read as a line.
        :else
        (do (.reset pending)
            (loop []
              (let [b (.read in)]
                (when-not (#{10 -1} b)
                  (recur))))
            (recur))))))

(defn- session!
  "One connection, from signing in until it ends."
  [{:keys [state config options] :as bot}]
  (with-open [socket (open config)]
    (.setSoTimeout socket (int (:tick-ms options)))
    (let [in (BufferedInputStream. (.getInputStream socket))
          pending (ByteArrayOutputStream.)
          out (OutputStreamWriter. (.getOutputStream socket) StandardCharsets/UTF_8)
          {:keys [nick password]} config]
      (swap! state
             assoc
             :out out
             :socket socket
             :nick nick
             :nick-tries 0
             :registered false
             :joined false
             :connected-at (System/currentTimeMillis)
             :heard-at (System/currentTimeMillis)
             :pinged false
             :rejoin-at nil)
      (when password (write! bot "CAP REQ :sasl"))
      (write! bot (irc/line "NICK" nick))
      (write! bot (irc/line "USER" nick "0" "*" "Jumble"))
      (loop []
        (when (:running @state)
          (let [line (read-line! in pending)]
            (cond
              (nil? line)
              (throw (java.io.IOException. "The IRC server closed the connection"))

              (= ::quiet line)
              (tick! bot)

              :else
              (do (swap! state assoc :heard-at (System/currentTimeMillis) :pinged false)
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
    ;; A connection that lasted starts the waits over; one dropped soon
    ;; after the welcome, as some servers do, does not.
    (when (>= (- (System/currentTimeMillis) (:connected-at @state 0)) (:stable-after-ms options))
      (swap! state assoc :attempt 0))
    (when (:running @state)
      (let [delays (:reconnect-delays-ms options)
            attempt (:attempt (swap! state update :attempt (fnil inc 0)))]
        (try
          (Thread/sleep (long (nth delays (min (dec attempt) (dec (count delays))))))
          ;; Stopped meanwhile.
          (catch InterruptedException _))))))

(defn start
  "Starts a bot for `config`, `{:host :port :tls :nick :password :channel
  :key}`, with `options` overriding the timings in [[defaults]] (tests)."
  ([config] (start config {}))
  ([config options]
   (let [state (atom {:running true :attempt 0})
         bot {:config config :options (merge defaults options) :state state}]
     (metrics/set-gauge! joined-gauge {} 0)
     (add-watch state
                ::joined
                (fn [_ _ before after]
                  (when (not= (boolean (:joined before)) (boolean (:joined after)))
                    (metrics/set-gauge! joined-gauge {} (if (:joined after) 1 0)))))
     (assoc bot
            :thread (Thread/startVirtualThread #(keep-connected! bot))
            :watchdog (Thread/startVirtualThread #(watch! bot))))))

(defn stop
  "Says goodbye and closes the connection, for good."
  [{:keys [state ^Thread thread ^Thread watchdog] :as bot}]
  (swap! state assoc :running false)
  ;; Not waited for long: the server may have stopped reading.
  (deref (future (write! bot (irc/line "QUIT" "Bye"))) 2000 nil)
  (when-let [^Socket socket (:socket @state)]
    (.close socket))
  (.interrupt thread)
  (.interrupt watchdog)
  (.join thread 5000))

(defrecord Bot [config bot]
  component/Lifecycle
  (start [this]
    (if (and config (nil? bot))
      (assoc this :bot (start config))
      this))
  (stop [this]
    (when bot (stop bot))
    (assoc this :bot nil)))

(defn component
  "The system's bot for `config` (see [[start]]); none when it is nil, IRC
  not being configured."
  [config]
  (map->Bot {:config config}))
