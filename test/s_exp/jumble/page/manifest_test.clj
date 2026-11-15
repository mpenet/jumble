(ns s-exp.jumble.page.manifest-test
  "The web app manifest, for adding the community to a phone's home screen."
  (:require [babashka.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [s-exp.jumble.fixtures :refer [db-fixture request]]
            [s-exp.jumble.page.assets :as assets]
            [s-exp.jumble.view.layout :as layout]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(deftest manifest-test
  (testing "anyone can fetch it: browsers ask for it without the session"
    (let [{:keys [status headers body]} (request :get "/manifest.webmanifest" nil
                                                 :ctx {:settings {:app-name "Swap Shop"}})
          manifest (json/read-str body {:key-fn identity})]
      (is (= 200 status))
      (is (= "application/manifest+json" (get headers "content-type")))
      (testing "named after the community, opening on its board, without the browser around it"
        (is (= "Swap Shop" (get manifest "name")))
        (is (= "/" (get manifest "start_url")))
        (is (= "standalone" (get manifest "display"))))
      (testing "with Jumble's icon in the sizes phones ask for, each served"
        (is (= [{"src" (assets/url "icon-192.png") "sizes" "192x192" "type" "image/png"}
                {"src" (assets/url "icon-512.png") "sizes" "512x512" "type" "image/png"}]
               (get manifest "icons")))
        (doseq [{:strs [src]} (get manifest "icons")]
          (is (= 200 (:status (request :get src nil))) src))))))

(deftest manifest-link-test
  (testing "every page points browsers to it"
    (is (str/includes? (layout/page {:csp-nonce "n" :ctx {:settings {:app-name "Jumble"}}} {} [:p "x"])
                       "<link rel=\"manifest\" href=\"/manifest.webmanifest\">"))))
