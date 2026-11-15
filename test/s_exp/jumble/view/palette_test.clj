(ns s-exp.jumble.view.palette-test
  "The palettes in the stylesheet: each keeps its text readable, light and
  dark, at a contrast of 4.5:1 at least."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.page.palette :as palette]))

(set! *warn-on-reflection* true)

(def ^:private css
  (delay (slurp (io/resource "public/app.css"))))

(defn- colours
  "`{name [light dark]}` of the light-dark() colours set in `block`."
  [block]
  (into {}
        (map (fn [[_ k light dark]] [k [light dark]]))
        (re-seq #"--([a-z-]+): light-dark\((#[0-9a-f]{6}), (#[0-9a-f]{6})\)" block)))

(defn- palettes
  "`{name colours}`: forest, the default, at :root, the others over it."
  []
  (let [root (colours (second (re-find #"(?s):root,\n\[data-palette=\"forest\"\] \{(.*?)\n\}" @css)))]
    (into {"forest" root}
          (map (fn [[_ k block]] [k (merge root (colours block))]))
          (re-seq #"(?s)\n\[data-palette=\"([a-z-]+)\"\] \{(.*?)\n\}" @css))))

(defn- luminance
  [hex]
  (let [channel (fn [i]
                  (let [c (/ (Integer/parseInt (subs hex i (+ i 2)) 16) 255.0)]
                    (if (<= c 0.03928) (/ c 12.92) (Math/pow (/ (+ c 0.055) 1.055) 2.4))))]
    (+ (* 0.2126 (channel 1)) (* 0.7152 (channel 3)) (* 0.0722 (channel 5)))))

(defn- contrast
  [a b]
  (let [[hi lo] (sort > [(luminance a) (luminance b)])]
    (/ (+ hi 0.05) (+ lo 0.05))))

(def ^:private text-on
  "Text colours and what they are read on."
  [["fg" "bg"] ["fg" "surface"] ["fg" "surface-sunken"]
   ["muted" "bg"] ["muted" "surface"] ["muted" "surface-sunken"]
   ["accent" "bg"] ["accent" "surface"] ["accent" "accent-soft"] ["accent-fg" "accent"]
   ["pop-text" "bg"] ["pop-text" "surface"] ["pop-fg" "pop"] ["fg" "pop-soft"]
   ["free" "free-soft"] ["lend" "lend-soft"] ["wanted" "wanted-soft"] ["danger" "danger-soft"] ["danger" "surface"] ["danger" "bg"]
   ["free" "bg"] ["free" "surface"] ["lend" "bg"] ["lend" "surface"] ["wanted" "bg"] ["wanted" "surface"]])

(deftest contrast-test
  (doseq [[palette cs] (palettes)
          [side i] [["light" 0] ["dark" 1]]
          [text on] text-on]
    (let [ratio (contrast (get-in cs [text i]) (get-in cs [on i]))]
      (is (>= ratio 4.5) (format "%s, %s: %s on %s is %.2f" palette side text on ratio)))))

(deftest palettes-test
  (testing "the palettes offered are those in the stylesheet"
    (is (= (set palette/names) (set (keys (palettes)))))))
