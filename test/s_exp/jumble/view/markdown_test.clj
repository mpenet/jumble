(ns s-exp.jumble.view.markdown-test
  "Descriptions' Markdown, rendered safely."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.onionpancakes.chassis.core :as h]
            [s-exp.jumble.view.markdown :as markdown]))

(set! *warn-on-reflection* true)

(defn- html [s] (h/html (markdown/render s)))

(deftest formatting-test
  (is (= "<div class=\"markdown\"><p><strong>Solid</strong> oak, <em>barely</em> used.</p></div>"
         (html "**Solid** oak, _barely_ used.")))
  (is (= "<div class=\"markdown\"><ul><li>two chairs</li><li>one table</li></ul></div>"
         (html "- two chairs\n- one table")))
  (testing "single line breaks are kept, as people type them"
    (is (= "<div class=\"markdown\"><p>Pickup after 5pm<br>or on weekends</p></div>"
           (html "Pickup after 5pm\nor on weekends"))))
  (testing "dollar amounts are text, not formulas"
    (is (str/includes? (html "Worth $20, asking $10") "Worth $20, asking $10"))))

(deftest links-test
  (testing "links to the web open without leaking the page or vouching for it"
    (is (str/includes? (html "[manual](https://example.com/manual)")
                       "<a href=\"https://example.com/manual\" rel=\"nofollow noopener noreferrer\">manual</a>")))
  (testing "bare URLs become links too"
    (is (str/includes? (html "See https://example.com/specs")
                       "<a href=\"https://example.com/specs\" rel=\"nofollow noopener noreferrer\">")))
  (testing "script and other dangerous URLs become plain text"
    (doseq [url ["javascript:alert(1)" "JavaScript:alert(1)" "vbscript:x" "data:text/html,x" "/relative" "//evil.example.com"]]
      (let [out (html (str "[click](" url ")"))]
        (is (not (str/includes? out "<a")) url)
        (is (str/includes? out "click") url))))
  (testing "#tags and [[wiki]] links point nowhere here, so they stay text"
    (is (not (str/includes? (html "#vintage [[chair]]") "<a")))))

(deftest unsafe-input-test
  (testing "raw HTML is shown as text, never interpreted"
    (let [out (html "<script>alert(1)</script>\n\nhi <img src=x onerror=alert(1)>")]
      (is (not (str/includes? out "<script")))
      (is (not (str/includes? out "<img")))
      (is (str/includes? out "&lt;script&gt;"))))
  (testing "images are not loaded; their description remains"
    (let [out (html "![a red bike](https://tracker.example.com/pixel.png)")]
      (is (not (str/includes? out "<img")))
      (is (not (str/includes? out "tracker")))
      (is (str/includes? out "a red bike"))))
  (testing "no headings competing with the page's own"
    (is (not (re-find #"<h\d" (html "# Big\n\nSmall\n---")))))
  (testing "no inline styles, which the content security policy refuses"
    (is (not (str/includes? (html "| a | b |\n|--:|:--|\n| 1 | 2 |") "style=")))))

(deftest blank-test
  (is (nil? (markdown/render "")))
  (is (nil? (markdown/render "   ")))
  (is (nil? (markdown/render nil))))

(deftest code-fence-test
  (testing "the language after ``` is never part of the markup"
    (is (= "<div class=\"markdown\"><pre><code>x = 1\n</code></pre></div>" (html "```x#q\nx = 1\n```")))
    (is (= "<div class=\"markdown\"><pre><code>x\n</code></pre></div>" (html "```a/b\nx\n```")))))

(deftest deep-nesting-test
  (testing "nesting too deep to render shows the text as paragraphs, instead of failing the page"
    (doseq [s [(apply str (repeat 3000 ">"))
               (str (apply str (repeat 3000 ">")) " hi\n\nnext <b>part</b>")]]
      (let [out (html s)]
        (is (str/starts-with? out "<div class=\"markdown\"><p>"))
        (is (not (str/includes? out "<b>")) "still escaped")))))
