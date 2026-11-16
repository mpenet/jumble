(ns s-exp.jumble.page.assets-test
  "Static files: their content-hashed addresses, and only those listed served."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [s-exp.ex :as ex]
            [s-exp.jumble.page.assets :as assets]
            [s-exp.jumble.error :as-alias error]
            [s-exp.jumble.token :as token]))

(set! *warn-on-reflection* true)

(defn- resource-bytes
  ^bytes [path]
  (with-open [in (io/input-stream (io/resource path))]
    (.readAllBytes in)))

(defn- serve
  [path]
  (assets/serve {:path-params {:file (subs path (count "/assets/"))}}))

(deftest vendored-datastar-test
  (testing "public/datastar.js is the unmodified upstream v1.0.4 bundle"
    (is (= "727844adfc825ee651fb93c544a2a739986f9a21820a94524b35f0cac470cf91"
           (token/sha256-hex (resource-bytes "public/datastar.js"))))))

(deftest url-test
  (testing "URLs carry a content hash, so a changed file gets a new URL"
    (is (re-matches #"/assets/datastar\.[0-9a-f]{10}\.js" (assets/url "datastar.js")))
    (is (re-matches #"/assets/app\.[0-9a-f]{10}\.css" (assets/url "app.css")))))

(deftest serve-test
  (let [{:keys [status headers body]} (serve (assets/url "datastar.js"))]
    (is (= 200 status))
    (is (= "text/javascript; charset=utf-8" (get headers "content-type")))
    (is (= "public, max-age=31536000, immutable" (get headers "cache-control")))
    (is (= (seq (resource-bytes "public/datastar.js")) (seq ^bytes body))))
  (is (= "text/css; charset=utf-8" (get-in (serve (assets/url "app.css")) [:headers "content-type"])))
  (is (= "image/png" (get-in (serve (assets/url "favicon.png")) [:headers "content-type"]))))

(deftest only-listed-assets-test
  (doseq [path ["/assets/datastar.js" "/assets/datastar.0000000000.js"
                "/assets/..%2Fconfig.edn" "/assets/config.edn"]]
    (is (= 404 (:status (serve path))) path)))

(deftest unknown-asset-test
  (testing "a file that is not served has no URL"
    (is (= ::error/unknown-asset
           (ex/ex-type (try
                         (assets/url "nope.js")
                         nil
                         (catch clojure.lang.ExceptionInfo e
                           e)))))))
