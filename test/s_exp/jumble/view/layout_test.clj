(ns s-exp.jumble.view.layout-test
  "The page shell: header, footer, a community's name and logo, message pages."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.page.assets :as assets]
            [s-exp.jumble.view.layout :as layout]))

(set! *warn-on-reflection* true)

(deftest page-test
  (let [html (layout/page {:csp-nonce "n0nce" :user {:name "<b>Al</b>"} :ctx {:settings {:app-name "Jumble"}}}
                          {:title "Desk lamp"}
                          [:p "body"])]
    (testing "document shell"
      (is (.startsWith ^String html "<!DOCTYPE html><html lang=\"en\" data-nonce=\"n0nce\">"))
      (is (str/includes? html "<title>Desk lamp · Jumble</title>"))
      (is (str/includes? html "<meta name=\"viewport\""))
      (is (str/includes? html "<p>body</p>")))
    (testing "assets by hashed URL, Datastar as a module"
      (is (str/includes? html (str "href=\"" (assets/url "app.css") "\"")))
      (is (str/includes? html (str "<script type=\"module\" src=\"" (assets/url "datastar.js") "\"></script>")))
      (is (str/includes? html (str "<link rel=\"icon\" href=\"" (assets/url "favicon.png") "\" type=\"image/png\">")))
      (is (str/includes? html (str "<link rel=\"apple-touch-icon\" href=\"" (assets/url "apple-touch-icon.png") "\">"))))
    (testing "the header shows the Jumble logo, in a version for each theme"
      (is (str/includes? html (str "<img class=\"wordmark only-light\" src=\"" (assets/url "logo.png") "\" alt=\"Jumble\"")))
      (is (str/includes? html (str "<img class=\"wordmark only-dark\" src=\"" (assets/url "logo-white.png") "\" alt=\"Jumble\""))
          "only one shows at a time, so both carry the name"))
    (testing "signed-in header shows the escaped name and a sign-out form"
      (is (str/includes? html "&lt;b&gt;Al&lt;/b&gt;"))
      (is (not (str/includes? html "<b>Al</b>")))
      (is (str/includes? html "<form method=\"post\" action=\"/logout\">")))))

(deftest navigation-icons-test
  (testing "each header link has an icon before its label"
    (let [html (layout/page {:csp-nonce "n" :user {:name "Al"} :moderator true :uri "/my/favorites"
                             :ctx {:settings {:app-name "Jumble"}}}
                            {} [:p "x"])]
      (doseq [[href icon label] [["/listings" "icon-grid" "Listings"] ["/my/listings" "icon-tag" "My listings"]
                                 ["/my/favorites" "icon-heart" "Favorites"] ["/messages" "icon-chat" "Messages"]
                                 ["/settings" "icon-user" "Settings"] ["/my/searches" "icon-search" "Saved searches"]
                                 ["/manage" "icon-shield" "Manage"]]]
        (is (re-find (re-pattern (str "<a href=\"" href "\"[^>]*><svg class=\"icon " icon "\"[^>]*>.*?</svg><span>" label "</span></a>"))
                     html)
            href)))))

(defn- menu
  "The account menu of `html`."
  [html]
  (re-find #"(?s)<details class=\"account-menu\".*?</details>" html))

(defn- main-links
  [html]
  (re-find #"(?s)<nav class=\"site-nav\".*?</nav>" html))

(deftest account-menu-test
  (let [page #(layout/page (merge {:csp-nonce "n" :user {:name "Al"} :ctx {:settings {:app-name "Jumble"}}} %) {} [:p "x"])]
    (testing "under the member's name: their settings, saved searches, and signing out"
      (let [m (menu (page {}))]
        (is (re-find #"<summary[^>]*>.*?<span class=\"user-name-text\">Al</span>.*?</summary>" m))
        (is (str/includes? m "href=\"/settings\""))
        (is (str/includes? m "href=\"/my/searches\""))
        (is (str/includes? m "<form method=\"post\" action=\"/logout\">"))
        (is (not (str/includes? m "href=\"/manage\"")))))
    (testing "for moderators, managing too, with what there is to review, flagged on the name"
      (let [html (page {:moderator true :open-reports (delay 2)})
            m (menu html)]
        (is (re-find #"href=\"/manage\"[^>]*>.*?<span>Manage</span><span class=\"badge\">2" m))
        (is (re-find #"<summary[^>]*>.*?<span class=\"menu-flag\"" m))
        (is (not (str/includes? (main-links html) "/manage")) "no longer among the main links")))
    (testing "no flag with nothing to review"
      (is (not (str/includes? (menu (page {:moderator true :open-reports (delay 0)})) "menu-flag"))))
    (testing "closed by a click elsewhere or Escape"
      (let [m (menu (page {}))]
        (is (str/includes? m "data-on:click__outside"))
        (is (str/includes? m "data-on:keydown__window"))))
    (testing "opened by pointing at it, where there is a pointer to hover with"
      (let [m (menu (page {}))]
        (is (re-find #"data-on:mouseenter=\"[^\"]*hover: hover" m))
        (is (str/includes? m "data-on:mouseleave"))))))

(deftest failed-datastar-requests-test
  (testing "a Datastar request answered with an error reloads the page, which
            then shows the login, error or not-found page for the current URL"
    (let [html (layout/page {:csp-nonce "n" :ctx {:settings {:app-name "Jumble"}}} {} [:p "x"])]
      (is (str/includes? html "<body data-on:datastar-fetch=\"evt.detail.type === &apos;error&apos; &amp;&amp; location.reload()\">")))))

(deftest anonymous-page-test
  (let [html (layout/page {:csp-nonce "n" :ctx {:settings {:app-name "Jumble"}}} {} [:p "x"])]
    (is (str/includes? html "<title>Jumble</title>"))
    (is (not (str/includes? html "/logout")))))

(deftest app-name-test
  (let [html (layout/page {:csp-nonce "n" :ctx {:settings {:app-name "Bazaar & Co"}}} {:title "Lamp"} [:p "x"])]
    (is (str/includes? html "<title>Lamp · Bazaar &amp; Co</title>"))
    (is (str/includes? html "<span>Bazaar &amp; Co</span></a>"))))

(deftest message-test
  (let [{:keys [status headers body]} (layout/message {:csp-nonce "n" :ctx {:settings {:app-name "Jumble"}}} 400 "Oops" "Try again.")]
    (is (= 400 status))
    (is (= "text/html; charset=utf-8" (get headers "content-type")))
    (is (str/includes? body "<h1>Oops</h1>"))
    (is (str/includes? body "<p>Try again.</p>"))))

(deftest renamed-community-test
  (testing "a community with its own name shows it next to the mark, not the Jumble logo"
    (let [html (layout/page {:csp-nonce "n" :user {:name "Al"} :ctx {:settings {:app-name "Swap Shop"}}} {} [:p "x"])]
      (is (str/includes? html (str "<img class=\"mark\" src=\"" (assets/url "mark.png") "\" alt=\"\"")))
      (is (str/includes? html "<span>Swap Shop</span>"))
      (is (not (str/includes? html (assets/url "logo.png")))))))

(deftest community-logo-test
  (let [logo {:file "logo.0123456789.svg"}
        dark {:file "logo-dark.abcdef0123.svg"}
        page #(layout/page {:csp-nonce "n" :user {:name "Al"} :ctx {:settings (merge {:app-name "Swap Shop"} %)}}
                           {} [:p "x"])]
    (testing "a community's logo leads home, named after it"
      (let [html (page {:logo logo})]
        (is (str/includes? html "<a class=\"brand\" href=\"/\"><img class=\"community-logo\" src=\"/brand/logo.0123456789.svg\" alt=\"Swap Shop\"></a>"))
        (is (not (str/includes? html (assets/url "logo.png"))))))
    (testing "with a version for the dark theme"
      (let [html (page {:logo logo :logo-dark dark})]
        (is (str/includes? html "<img class=\"community-logo only-light\" src=\"/brand/logo.0123456789.svg\" alt=\"Swap Shop\">"))
        (is (str/includes? html "<img class=\"community-logo only-dark\" src=\"/brand/logo-dark.abcdef0123.svg\" alt=\"Swap Shop\">"))))
    (testing "an instance of its own credits Jumble in the footer"
      (is (str/includes? (page {:logo logo}) "Powered by Jumble"))
      (is (str/includes? (page {}) "Powered by Jumble") "a name of its own is enough")
      (is (not (str/includes? (layout/page {:csp-nonce "n" :ctx {:settings {:app-name "Jumble"}}} {} [:p "x"])
                              "Powered by Jumble"))))))

(deftest palette-test
  (testing "the page carries the community's palette, for the stylesheet"
    (is (str/includes? (layout/page {:csp-nonce "n" :palette (delay "ink") :ctx {:settings {:app-name "Jumble"}}} {:title "x"} [:p "b"])
                       "data-palette=\"ink\""))))

(deftest footer-test
  (let [html (layout/page {:csp-nonce "n" :ctx {:settings {:app-name "Jumble" :source-url "https://git.example.org/our-jumble"}}}
                          {:title "x"} [:p "body"])]
    (testing "the footer links to the privacy page, the source this instance runs and its makers"
      (is (str/includes? html "<a href=\"/privacy\">Privacy</a>"))
      (is (str/includes? html "<a href=\"https://git.example.org/our-jumble\">Source code</a>"))
      (is (str/includes? html "Built by <a href=\"https://s-exp.com\">s-exp.com</a>")))
    (testing "and to Jumble's own site, for a community of one's own"
      (is (str/includes? html "<a href=\"https://jumbl.ing\">Get your own Jumble</a>")))
    (testing "and says who hosts the instance when set"
      (is (not (str/includes? html "Hosted by")))
      (let [hosted #(layout/page {:csp-nonce "n" :ctx {:settings {:app-name "Jumble" :hosted-by %}}} {:title "x"} [:p "body"])]
        (is (str/includes? (hosted {:name "Jumble" :url "https://jumbl.ing"})
                           "Hosted by <a href=\"https://jumbl.ing\">Jumble</a>"))
        (is (str/includes? (hosted {:name "Club IT"}) "Hosted by Club IT"))))))
