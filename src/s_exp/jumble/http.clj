(ns s-exp.jumble.http
  "HTTP requests with the JDK's client, for storage and sign-in: HTTP/1.1,
  redirects never followed, error statuses returned rather than thrown."
  (:require [clojure.string :as str])
  (:import (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpClient$Version HttpHeaders HttpRequest
                          HttpRequest$BodyPublisher HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandler HttpResponse$BodyHandlers)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(defn client
  "A client connecting within `connect-timeout` ms, whose requests wait at
  most `timeout` ms for a response (none when nil)."
  [{:keys [connect-timeout timeout]}]
  {:http-client (-> (HttpClient/newBuilder)
                    (.version HttpClient$Version/HTTP_1_1)
                    (.followRedirects HttpClient$Redirect/NEVER)
                    (.connectTimeout (Duration/ofMillis connect-timeout))
                    (.build))
   :timeout timeout})

(defn- publisher
  ^HttpRequest$BodyPublisher [body]
  (cond
    (nil? body) (HttpRequest$BodyPublishers/noBody)
    (bytes? body) (HttpRequest$BodyPublishers/ofByteArray body)
    :else (HttpRequest$BodyPublishers/ofString (str body))))

(defn- handler
  ^HttpResponse$BodyHandler [as]
  (case as
    :string (HttpResponse$BodyHandlers/ofString)
    :stream (HttpResponse$BodyHandlers/ofInputStream)))

(defn- header-map
  "Each header's first value by lowercase name."
  [^HttpHeaders headers]
  (into {}
        (map (fn [[k vs]] [(str/lower-case k) (first vs)]))
        (.map headers)))

(defn request
  "Sends `{:method :url :headers :body}` (body bytes, a string or nil) and
  returns `{:status :headers :body}`, the body read `as` :string or :stream
  (an InputStream the caller must close).
  Network failures and timeouts throw IOExceptions."
  [{:keys [^HttpClient http-client timeout]} {:keys [method url headers body]} as]
  (let [builder (doto (HttpRequest/newBuilder (URI. url))
                  (.method (str/upper-case (name method)) (publisher body)))]
    (doseq [[k v] headers]
      (.header builder k v))
    (when timeout
      (.timeout builder (Duration/ofMillis timeout)))
    (let [^HttpResponse response (.send http-client (.build builder) (handler as))]
      {:status (.statusCode response)
       :headers (header-map (.headers response))
       :body (.body response)})))
