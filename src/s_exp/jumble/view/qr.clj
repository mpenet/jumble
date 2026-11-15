(ns s-exp.jumble.view.qr
  "QR codes, drawn as SVG: sharp at any size, printed or on screen, and no
  image to serve."
  (:require [clojure.string :as str])
  (:import (io.nayuki.qrcodegen QrCode QrCode$Ecc)))

(set! *warn-on-reflection* true)

(def ^:private quiet-zone
  "Light modules around the code, which readers need to find it."
  4)

(def ^:private corrections
  {:medium QrCode$Ecc/MEDIUM
   :high QrCode$Ecc/HIGH})

(defn modules
  "The code for `text`, as rows of booleans, true for a dark module. Medium
  error correction unless `correction` says :high: a poster may be creased
  or stained, and a mark may cover its middle."
  ([text] (modules text :medium))
  ([^String text correction]
   (let [code (QrCode/encodeText text ^QrCode$Ecc (corrections correction))
         n (.-size code)]
     (vec (for [y (range n)]
            (vec (for [x (range n)] (.getModule code x y))))))))

(defn mark-area
  "The modules a mark in the middle of an `n`-module code covers, as a set
  of [x y]: a square a quarter of its side or so, within what high error
  correction makes up for."
  [n]
  (let [side (let [s (quot n 4)] (if (= (odd? s) (odd? n)) s (inc s)))
        start (quot (- n side) 2)]
    (set (for [x (range start (+ start side)) y (range start (+ start side))] [x y]))))

(defn svg
  "The code for `text` as an SVG element, its quiet zone included, read as
  `label` by those who cannot see it; with `:mark`, the address of an image
  drawn in its middle, on modules left light for it."
  ([text label] (svg text label nil))
  ([text label {:keys [mark]}]
   (let [rows (modules text (if mark :high :medium))
         n (count rows)
         cleared (if mark (mark-area n) #{})
         side (+ n (* 2 quiet-zone))
         area-side (Math/sqrt (count cleared))
         offset (+ quiet-zone (/ (- n area-side) 2))]
     [:svg {:class "qr" :viewBox (str "0 0 " side " " side) :role "img" :aria-label label
            :xmlns "http://www.w3.org/2000/svg" :shape-rendering "crispEdges"}
      [:path {:fill "currentColor"
              :d (str/join (for [[y row] (map-indexed vector rows)
                                 [x dark] (map-indexed vector row)
                                 :when (and dark (not (cleared [x y])))]
                             (str "M" (+ x quiet-zone) " " (+ y quiet-zone) "h1v1h-1z")))}]
      (when mark
        ;; A module's margin all round, so the mark does not touch the code.
        [:image {:href mark :x (inc offset) :y (inc offset) :width (- area-side 2) :height (- area-side 2)}])])))
