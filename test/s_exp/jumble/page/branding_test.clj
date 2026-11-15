(ns s-exp.jumble.page.branding-test
  "A community's own logo: read and checked, then served."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [s-exp.ex :as ex]
            [s-exp.jumble.page.branding :as branding]
            [s-exp.jumble.error :as-alias error]
            [s-exp.jumble.fixtures :as fixtures]))

(set! *warn-on-reflection* true)

(def ^:private svg "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\"/></svg>")

(defn- file
  "A temporary file named `name` holding `content` (a string or bytes)."
  [name content]
  (let [f (io/file (System/getProperty "java.io.tmpdir") (str (random-uuid) "-" name))]
    (.deleteOnExit f)
    (if (string? content) (spit f content) (io/copy ^bytes content f))
    (str f)))

(defn- refusal
  [path]
  (try (branding/read-logo path) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest read-logo-test
  (testing "an SVG or a PNG, by content, under 200 KB"
    (let [logo (branding/read-logo (file "logo.svg" svg))]
      (is (= "image/svg+xml" (:content-type logo)))
      (is (re-matches #"logo\.[0-9a-f]{10}\.svg" (:file logo))))
    (let [png (.readAllBytes (io/input-stream (io/resource "public/mark.png")))]
      (is (= "image/png" (:content-type (branding/read-logo (file "logo.png" png)))))))
  (testing "anything else stops startup, saying why"
    (is (re-find #"not found" (refusal "/nonexistent/logo.svg")))
    (is (re-find #"SVG or PNG" (refusal (file "logo.gif" "GIF89a"))))
    (is (re-find #"SVG or PNG" (refusal (file "logo.svg" "plain text"))) "judged by content, not name")
    (is (re-find #"200 KB" (refusal (file "logo.svg" (str "<svg>" (apply str (repeat 210000 " ")) "</svg>")))))))

(deftest serve-test
  (let [logo (branding/read-logo (file "logo.svg" svg))
        handler (fixtures/handler {:settings {:logo logo}})
        get-path (fn [path] (handler {:request-method :get :uri path :headers {}}))]
    (testing "served at its content-hashed address, cached for good"
      (let [{:keys [status headers body]} (get-path (branding/url logo))]
        (is (= 200 status))
        (is (= "image/svg+xml" (get headers "content-type")))
        (is (str/includes? (get headers "cache-control") "immutable"))
        (is (= svg (String. ^bytes body "UTF-8")))
        (testing "an SVG opened on its own cannot run scripts"
          (is (str/includes? (get headers "content-security-policy") "sandbox"))
          (is (str/includes? (get headers "content-security-policy") "script-src 'none'")))))
    (testing "nothing else under that path"
      (is (= 404 (:status (get-path "/brand/logo.0000000000.svg")))))))

(deftest refusal-type-test
  (testing "a refused logo is an invalid logo"
    (is (= ::error/invalid-logo (ex/ex-type (try (branding/read-logo (file "logo.txt" "hello")) nil
                                                 (catch clojure.lang.ExceptionInfo e e)))))))
