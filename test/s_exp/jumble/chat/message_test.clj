(ns s-exp.jumble.chat.message-test
  "What the community's chat channel is told of a new post: its title, what
  it is, its category and pickup, and a link; nothing of who posted it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.chat.message :as message]
            [s-exp.jumble.irc.protocol :as irc]
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
    (is (str/starts-with? (binding [i18n/*catalog* (i18n/catalog "fr")]
                            (message/text settings lamp))
                          "Nouveau sur Swap Shop : Desk lamp"))))

(deftest mentions-test
  (testing "a title cannot ping the channel, nor be read as markup"
    (let [text (message/text settings (assoc lamp :title "<!channel> @everyone @here @all & <https://x|y>"))]
      (is (not (str/includes? text "<!channel>")))
      (is (str/includes? text "&lt;!channel&gt;"))
      (is (str/includes? text "&amp;"))
      (doseq [mention ["@everyone" "@here" "@all"]]
        (is (not (str/includes? text mention)) mention)))))

(deftest irc-line-test
  (testing "for IRC, one line with the link, as written: IRC has no markup to escape"
    (is (= "New on Swap Shop: Desk lamp · CHF 25 · Home · Main hall · https://swap.example.org/listings/42"
           (message/irc-line settings lamp)))
    (is (str/includes? (message/irc-line settings (assoc lamp :title "Pots & pans")) "Pots & pans"))))

(deftest injection-test
  (testing "what a member wrote stays on the summary's line: no line of their own, as a link that looks like ours"
    (let [text (message/text settings (assoc lamp :pickup-place nil :pickup-city "Paris\nhttps://evil.example/listings/42"))]
      (is (= 1 (count (re-seq #"\n" text))))
      (is (str/ends-with? text "\nhttps://swap.example.org/listings/42"))))
  (testing "no Markdown link nor image, which Mattermost and Discord show"
    (let [text (message/text settings (assoc lamp :title "[https://swap.example.org/listings/1](https://evil.example) ![](https://tracker.example/p.png)"))]
      (is (not (str/includes? text "](")))
      (is (not (str/includes? text "![")))))
  (testing "no control of the text's direction, which could turn the line, link included, around"
    (doseq [line [(message/text settings (assoc lamp :title "lamp \u202Egnp.live"))
                  (message/irc-line settings (assoc lamp :title "lamp \u202Egnp.live \u2066x\u2069"))]]
      (is (not (re-find #"[\u202A-\u202E\u2066-\u2069\u200E\u200F\u061C]" line)) line))))

(deftest irc-length-test
  (testing "however long the post in bytes, the IRC line fits and keeps its link whole"
    (let [line (message/irc-line settings
                                 (assoc lamp
                                        :title (apply str (repeat 120 "家"))
                                        :pickup-place nil
                                        :pickup-city (apply str (repeat 80 "é"))))]
      (is (str/ends-with? line " · https://swap.example.org/listings/42"))
      (is (<= (alength (.getBytes ^String line java.nio.charset.StandardCharsets/UTF_8)) irc/max-text-bytes)))))
