(ns s-exp.jumble.http-test
  "The HTTP client, against a local server; its with-server for other tests."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.http :as http])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io IOException InputStream)
           (java.net InetSocketAddress)
           (java.net.http HttpTimeoutException)))

(set! *warn-on-reflection* true)

(defn with-server
  "Calls `f` with the base URL of a local server answering every request
  with `(respond exchange)`, which returns [status headers body-string];
  requests seen are conj'd onto `seen` as maps."
  [seen respond f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server
                    "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [^HttpExchange exchange exchange
                              body (with-open [in (.getRequestBody exchange)]
                                     (slurp in))]
                          (swap! seen
                                 conj
                                 {:method (.getRequestMethod exchange)
                                  :path (str (.getRawPath (.getRequestURI exchange)))
                                  :headers (into {}
                                                 (map (fn [[k v]] [(.toLowerCase ^String k) (first v)]))
                                                 (.getRequestHeaders exchange))
                                  :body body})
                          (let [[status headers ^String out] (respond exchange)
                                bs (.getBytes (or out "") "UTF-8")]
                            (doseq [[k v] headers]
                              (.add (.getResponseHeaders exchange) k v))
                            (.sendResponseHeaders exchange (int status) (if (zero? (alength bs)) -1 (alength bs)))
                            (with-open [o (.getResponseBody exchange)]
                              (.write o bs)))))))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
      (finally
        (.stop server 0)))))

(deftest request-test
  (let [seen (atom [])
        client (http/client {:connect-timeout 1000})]
    (with-server seen (fn [_] [201 {"Content-Type" "text/plain" "X-Thing" "a"} "made"])
      (fn [base]
        (testing "method, headers and body go out; status, headers (lowercase) and body come back"
          (let [{:keys [status headers body]} (http/request client
                                                            {:method :put :url (str base "/b/k%20y")
                                                             :headers {"x-amz-date" "20261030T000000Z"}
                                                             :body (.getBytes "photo")}
                                                            :string)]
            (is (= 201 status))
            (is (= "made" body))
            (is (= "text/plain" (get headers "content-type")))
            (is (= "a" (get headers "x-thing")))
            (is (= {:method "PUT" :path "/b/k%20y" :body "photo"}
                   (select-keys (peek @seen) [:method :path :body])))
            (is (= "20261030T000000Z" (get-in (peek @seen) [:headers "x-amz-date"])))))
        (testing "a string body, and the response as a stream"
          (let [{:keys [^InputStream body]} (http/request client {:method :post :url base :body "a=1"} :stream)]
            (is (= "made" (with-open [in body] (slurp in))))
            (is (= "a=1" (:body (peek @seen))))))
        (testing "no body for GET and DELETE"
          (http/request client {:method :delete :url base} :string)
          (is (= {:method "DELETE" :body ""} (select-keys (peek @seen) [:method :body]))))))
    (testing "error statuses are returned, not thrown"
      (with-server seen (fn [_] [404 {} "missing"])
        (fn [base] (is (= 404 (:status (http/request client {:method :get :url base} :string)))))))
    (testing "redirects are not followed"
      (with-server seen (fn [_] [302 {"Location" "http://127.0.0.1:1/elsewhere"} nil])
        (fn [base] (is (= 302 (:status (http/request client {:method :get :url base} :string)))))))
    (testing "a server too slow to answer times out with an IOException"
      (with-server seen (fn [_] (Thread/sleep 2000) [200 {} "late"])
        (fn [base]
          (is (thrown? HttpTimeoutException (http/request (http/client {:connect-timeout 1000 :timeout 200})
                                                          {:method :get :url base}
                                                          :string))))))
    (testing "nothing listening is an IOException"
      (is (thrown? IOException (http/request client {:method :get :url "http://127.0.0.1:1/"} :string))))))
