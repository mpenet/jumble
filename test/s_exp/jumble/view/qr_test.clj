(ns s-exp.jumble.view.qr-test
  "QR codes, drawn as SVG: read back, they say what they were given."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.view.qr :as qr])
  (:import (com.google.zxing BinaryBitmap MultiFormatReader)
           (com.google.zxing.client.j2se BufferedImageLuminanceSource)
           (com.google.zxing.common HybridBinarizer)
           (java.awt.image BufferedImage)))

(set! *warn-on-reflection* true)

(defn- image
  "The code's modules as a picture, 4 pixels each, with its quiet zone."
  ^BufferedImage [modules]
  (let [n (count modules)
        scale 4
        margin 4
        size (* scale (+ n (* 2 margin)))
        img (BufferedImage. size size BufferedImage/TYPE_INT_RGB)]
    (doseq [x (range size) y (range size)]
      (let [mx (- (quot x scale) margin)
            my (- (quot y scale) margin)
            dark (and (< -1 mx n) (< -1 my n) (get-in modules [my mx]))]
        (.setRGB img x y (if dark 0x000000 0xffffff))))
    img))

(defn- read-back
  [modules]
  (.getText (.decode (MultiFormatReader.)
                     (BinaryBitmap. (HybridBinarizer. (BufferedImageLuminanceSource. (image modules)))))))

(deftest modules-test
  (testing "read back, the code says what it was given"
    (is (= "https://swap.example.org" (read-back (qr/modules "https://swap.example.org")))))
  (testing "square, odd-sided as QR codes are"
    (let [modules (qr/modules "https://swap.example.org")]
      (is (apply = (count modules) (map count modules)))
      (is (odd? (count modules))))))

(deftest svg-test
  (let [modules (qr/modules "https://swap.example.org")
        n (count modules)
        dark (count (filter true? (flatten modules)))
        [tag attrs path] (qr/svg "https://swap.example.org" "Scan to join")]
    (testing "an SVG with its quiet zone, labelled for those who cannot see it"
      (is (= :svg tag))
      (is (= (str "0 0 " (+ n 8) " " (+ n 8)) (:viewBox attrs)))
      (is (= "img" (:role attrs)))
      (is (= "Scan to join" (:aria-label attrs))))
    (testing "one square per dark module, in one path"
      (is (= dark (count (re-seq #"M" (get-in path [1 :d]))))))))

(deftest mark-test
  (let [text "https://swap.example.org"
        marked (qr/svg text "Scan to join" {:mark "/assets/mark.png"})
        modules (qr/modules text :high)
        n (count modules)
        cleared (qr/mark-area n)]
    (testing "with a mark in its middle, the code has room enough lost to still read"
      (is (= text (read-back (vec (for [[y row] (map-indexed vector modules)]
                                    (vec (for [[x dark] (map-indexed vector row)]
                                           (and dark (not (cleared [x y]))))))))))
      (is (< (count (qr/modules text)) n) "at the highest correction, a larger code"))
    (testing "the mark drawn over the cleared middle"
      (is (= "/assets/mark.png" (get-in (last marked) [1 :href]))))))
