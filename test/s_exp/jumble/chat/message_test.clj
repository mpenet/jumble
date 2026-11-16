(ns s-exp.jumble.chat.message-test
  "What the community's chat channel is told of a new post: its title, what
  it is, its category and pickup, and a link; nothing of who posted it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.chat.message :as message]
            [s-exp.jumble.view.i18n :as i18n]))

(set! *warn-on-reflection* true)

(def ^:private settings
  {:base-url "https://swap.example.org" :app-name "Swap Shop" :currency "CHF"
   :categories [{:id "home" :label "Home"}]
   :places [{:id "hall" :label "Main hall"}]})

(def ^:private lamp
  {:id 42 :title "Desk lamp" :kind "sale" :price-minor 2500 :category "home" :pickup-place "hall"})

(deftest text-test
  (testing "the post in one line, and a link to it"
    (is (= "New on Swap Shop: Desk lamp · CHF 25 · Home · Main hall\nhttps://swap.example.org/listings/42"
           (message/text settings lamp))))
  (testing "what each kind is"
    (is (str/includes? (message/text settings (assoc lamp :kind "free")) "Desk lamp · Free · "))
    (is (str/includes? (message/text settings (assoc lamp :kind "lend")) "Desk lamp · To lend · "))
    (is (str/includes? (message/text settings (assoc lamp :kind "wanted")) "Desk lamp · Wanted · ")))
  (testing "picked up in a city"
    (is (str/includes? (message/text settings (assoc lamp :pickup-place nil :pickup-city "Lausanne")) "· Home · Lausanne\n")))
  (testing "in the community's language"
    (is (str/starts-with? (binding [i18n/*catalog* (i18n/catalog "fr")] (message/text settings lamp))
                          "Nouveau sur Swap Shop : Desk lamp"))))

(deftest mentions-test
  (testing "a title cannot ping the channel, nor be read as markup"
    (let [text (message/text settings (assoc lamp :title "<!channel> @everyone @here @all & <https://x|y>"))]
      (is (not (str/includes? text "<!channel>")))
      (is (str/includes? text "&lt;!channel&gt;"))
      (is (str/includes? text "&amp;"))
      (doseq [mention ["@everyone" "@here" "@all"]]
        (is (not (str/includes? text mention)) mention)))))
