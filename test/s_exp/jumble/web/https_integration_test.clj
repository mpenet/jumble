(ns ^:integration s-exp.jumble.web.https-integration-test
  "HTTPS served by the app with a certificate from the docker compose
  Pebble, a test ACME CA."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ol.clave.automation :as acme]
            [s-exp.enso :as enso]
            [s-exp.jumble.http :as http]
            [s-exp.jumble.web.https :as https])
  (:import (java.io File)
           (java.net InetAddress ServerSocket)
           (java.net.http HttpClient)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.security KeyStore)
           (java.security.cert CertificateFactory X509Certificate)
           (java.util List)
           (javax.net.ssl SNIHostName SNIServerName SSLContext SSLSocket TrustManagerFactory)))

(set! *warn-on-reflection* true)

(def ^:private domain "jumble.test")

(defn- trusting
  "An SSLContext trusting only the certificates in PEM `text`."
  ^SSLContext [^String text]
  (let [store (doto (KeyStore/getInstance (KeyStore/getDefaultType)) (.load nil nil))
        certs (.generateCertificates (CertificateFactory/getInstance "X.509")
                                     (io/input-stream (.getBytes text "UTF-8")))]
    (doseq [[i cert] (map-indexed vector certs)]
      (.setCertificateEntry store (str "ca" i) cert))
    (doto (SSLContext/getInstance "TLS")
      (.init nil (.getTrustManagers (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
                                      (.init store)))
             nil))))

(def ^:private pebble-api
  "Pebble's own API, served with its test CA's certificate."
  (delay (-> (HttpClient/newBuilder)
             (.sslContext (trusting (slurp (io/resource "pebble.minica.pem"))))
             (.build))))

(defn- issuing-root
  "The root Pebble signs certificates with, made anew each time it starts."
  []
  (let [{:keys [body]} (http/request {:http-client @pebble-api}
                                     {:method :get :url "https://localhost:15000/roots/0"}
                                     :string)]
    body))

(defn- https-get
  "GET / on 127.0.0.1:`port` over TLS for `domain`, trusting only `roots`:
  the certificate presented, and the response's status line and Alt-Svc
  header."
  [^SSLContext roots port]
  (with-open [socket ^SSLSocket (.createSocket (.getSocketFactory roots) "127.0.0.1" (int port))]
    (let [params (doto (.getSSLParameters socket)
                   (.setServerNames (List/of ^SNIServerName (SNIHostName. ^String domain))))]
      (.setSSLParameters socket params)
      (.startHandshake socket)
      (let [out (.getOutputStream socket)]
        (.write out (.getBytes (str "GET / HTTP/1.1\r\nHost: " domain "\r\nConnection: close\r\n\r\n") "UTF-8"))
        (.flush out))
      (let [[status-line & headers] (take-while seq (line-seq (io/reader (.getInputStream socket))))]
        {:certificate (first (.getPeerCertificates (.getSession socket)))
         :status-line status-line
         :alt-svc (some #(second (re-matches #"(?i)alt-svc: *(.*)" %)) headers)}))))

(defn- served
  "What [[https-get]] gets once the certificate is in, within 60 s."
  [roots port]
  (loop [tries 120]
    (let [result (try (https-get roots port) (catch java.io.IOException _ nil))]
      (cond
        result result
        (pos? tries) (do (Thread/sleep 500) (recur (dec tries)))
        :else (throw (ex-info "No certificate served" {:port port}))))))

(defn- free-port
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- start
  [^File dir port & [http-port]]
  (https/start (fn [_] {:status 200 :body "hello"})
               {:host "127.0.0.1"
                :http-port (or http-port 0)
                :base-url (str "https://" domain)
                :https {:port port
                        :domain domain
                        :acme-directory "https://localhost:14000/dir"
                        :certificates (str dir)}
                ;; Changed certificate files are served within 200 ms, and
                ;; checked against the current certificate as often.
                :server-opts {:ssl-cert-reload-interval 200 :http3-cert-reload-interval 200}
                :check-interval-ms 200
                :http-client @pebble-api}))

(defn- names
  [^X509Certificate cert]
  (set (map second (.getSubjectAlternativeNames cert))))

(deftest https-test
  (let [dir (.toFile (Files/createTempDirectory "jumble-certificates" (make-array FileAttribute 0)))
        roots (trusting (issuing-root))
        port (free-port)]
    (try
      (let [stored-certificate
            (let [server (start dir port)]
              (try
                (testing "HTTPS with a certificate for the domain, once the CA issued it"
                  (let [{:keys [certificate status-line alt-svc]} (served roots port)]
                    (is (= #{domain} (names certificate)))
                    (is (= "HTTP/1.1 200 OK" status-line))
                    (testing "HTTP/3 offered on the same port"
                      (is (str/includes? (str alt-svc) (str "h3=\":" port "\""))))
                    (testing "a renewed certificate is served without a restart"
                      (acme/renew-managed (:automation server))
                      (let [renewed (loop [tries 120]
                                      (let [served-now (:certificate (served roots port))]
                                        (if (and (= certificate served-now) (pos? tries))
                                          (do (Thread/sleep 500) (recur (dec tries)))
                                          served-now)))]
                        (is (not= certificate renewed))
                        renewed))))
                (finally
                  (https/stop server))))]
        (testing "a restart serves the stored certificate rather than asking for another"
          (let [server (start dir port)]
            (try
              (is (some? @(:tls server)) "served at once")
              (is (= stored-certificate (:certificate (served roots port))))
              (finally
                (https/stop server)))))
        (testing "a start that fails leaves nothing listening"
          (let [http-port (free-port)]
            ;; The stored certificate is served at once: on a port taken.
            (with-open [_ (ServerSocket. (int port) 50 (InetAddress/getByName "127.0.0.1"))]
              (is (thrown? Exception (start dir port http-port))))
            (https/stop (start dir port http-port))))
        (testing "certificate files that went astray are written again"
          (let [server (start dir port)
                file (io/file dir "serving" "certificate.pem")
                written (slurp file)]
            (try
              (spit file "not a certificate")
              (is (= written (loop [tries 50]
                               (let [now (slurp file)]
                                 (if (and (not= written now) (pos? tries))
                                   (do (Thread/sleep 100) (recur (dec tries)))
                                   now)))))
              (finally
                (https/stop server)))))
        (testing "plain HTTP sends to HTTPS"
          (let [server (start dir port)]
            (try
              (is (= {:status 308 :location (str "https://" domain "/listings")}
                     (let [{:keys [status headers]} (http/request (http/client {:connect-timeout 5000})
                                                                  {:method :get
                                                                   :url (str "http://127.0.0.1:" (enso/port (:plain server)) "/listings")}
                                                                  :string)]
                       {:status status :location (get headers "location")})))
              (finally
                (https/stop server))))))
      (finally
        (doseq [^File f (reverse (file-seq dir))]
          (when (str/starts-with? (.getPath f) (.getPath dir))
            (.delete f)))))))
