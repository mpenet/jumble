(ns s-exp.jumble.image-test
  "Photos through vips: orientation, metadata stripped, sizes, refusals,
  limits, and waiting a turn."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [s-exp.ex :as ex]
            [s-exp.jumble.error :as-alias error]
            [s-exp.jumble.image :as image])
  (:import (java.awt GraphicsEnvironment)
           (java.awt.image BufferedImage)
           (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.lang.management ManagementFactory)
           (javax.imageio ImageIO)))

(set! *warn-on-reflection* true)

(deftest headless-test
  (testing "the tests and the development seed draw images without a display:
  on macOS, AWT would otherwise start a Dock app that takes the focus"
    (is (GraphicsEnvironment/isHeadless))))

(defn- fixture
  ^bytes [name]
  (with-open [in (io/input-stream (io/resource (str "images/" name)))]
    (.readAllBytes in)))

(defn- decode
  ^BufferedImage [^bytes bs]
  (ImageIO/read (ByteArrayInputStream. bs)))

(defn- rgb
  [^BufferedImage img x y]
  (let [p (.getRGB img (int x) (int y))]
    [(bit-and (bit-shift-right p 16) 0xff) (bit-and (bit-shift-right p 8) 0xff) (bit-and p 0xff)]))

(defn- reddish? [[r g b]] (and (> r 180) (< g 80) (< b 80)))
(defn- bluish? [[r g b]] (and (< r 80) (< g 80) (> b 180)))

(defn- contains-bytes?
  [^bytes haystack ^String needle]
  (.contains (String. haystack "ISO-8859-1") needle))

(defn- jpeg
  ^bytes [w h]
  (let [out (ByteArrayOutputStream.)]
    (ImageIO/write (BufferedImage. w h BufferedImage/TYPE_INT_RGB) "jpg" out)
    (.toByteArray out)))

(defn- reason
  [bs]
  (try
    (image/process bs)
    ::accepted
    (catch clojure.lang.ExceptionInfo e
      (ex/ex-type e))))

(deftest orientation-and-metadata-test
  (let [{:keys [full thumb]} (image/process (fixture "rotated-gps.jpg"))
        img (decode (:bytes full))]
    (testing "EXIF orientation is applied to the pixels: 40x20 stored, shown 20x40"
      (is (= [20 40] [(:width full) (:height full)]))
      (is (= [20 40] [(.getWidth img) (.getHeight img)]))
      (is (reddish? (rgb img 10 5)) "the stored left half ends up on top")
      (is (bluish? (rgb img 10 34))))
    (testing "no metadata survives: no EXIF block, no GPS, no camera details"
      (doseq [bs [(:bytes full) (:bytes thumb)]]
        (is (not (contains-bytes? bs "Exif")))
        (is (not (contains-bytes? bs "PhoneMaker")))))
    (testing "small images are not enlarged"
      (is (= [20 40] [(:width thumb) (:height thumb)])))))

(deftest heif-test
  (doseq [name ["rotated-gps.heic" "rotated-gps.avif"]]
    (testing (str name ", as phones save photos: stored as a JPEG, upright, without metadata")
      (let [{:keys [full thumb]} (image/process (fixture name))
            img (decode (:bytes full))]
        (is (= [20 40] [(:width full) (:height full)]))
        (is (reddish? (rgb img 10 5)))
        (is (bluish? (rgb img 10 34)))
        (doseq [bs [(:bytes full) (:bytes thumb)]]
          (is (not (contains-bytes? bs "Exif")))
          (is (not (contains-bytes? bs "PhoneMaker"))))))))

(defn- declaring
  "`bs`, a HEIF file, its image sizes (`ispe` boxes) said to be `w` by `h`."
  ^bytes [^bytes bs w h]
  (let [out (aclone bs)
        text (String. out "ISO-8859-1")]
    (loop [from 0]
      (let [i (.indexOf text "ispe" (int from))]
        (when (>= i 0)
          (doseq [[offset v] [[8 w] [12 h]]
                  k (range 4)]
            (aset-byte out (+ i offset k) (unchecked-byte (bit-shift-right v (* 8 (- 3 k))))))
          (recur (inc i)))))
    out))

(deftest heif-size-test
  (testing "a HEIF's size is read from its header: too many pixels refused before decoding"
    (is (= ::error/too-many-pixels (reason (declaring (fixture "rotated-gps.heic") 20000 20000))))
    (is (= ::error/too-many-pixels (reason (declaring (fixture "rotated-gps.avif") 20000 20000)))))
  (testing "a HEIF header saying no size is no image"
    (let [bs (fixture "rotated-gps.heic")
          cut (java.util.Arrays/copyOf bs 40)]
      (is (= ::error/not-an-image (reason cut))))))

(deftest sizes-test
  (let [{:keys [full thumb]} (image/process (jpeg 3200 2400))]
    (is (= [1600 1200] [(:width full) (:height full)]))
    (is (= [480 360] [(:width thumb) (:height thumb)]))
    (is (= [480 360] (let [img (decode (:bytes thumb))] [(.getWidth img) (.getHeight img)])))))

(deftest transparency-test
  (testing "transparent PNG areas become white, not black, in the JPEG"
    (let [img (decode (:bytes (:full (image/process (fixture "transparent.png")))))]
      (is (every? #(> % 240) (rgb img 10 10))))))

(deftest rejections-test
  (is (= ::error/too-many-pixels (reason (fixture "huge.png"))) "50 megapixels, refused before decoding")
  (is (= ::error/unsupported-format (reason (fixture "tiny.gif"))))
  (is (= ::error/unsupported-format (reason (.getBytes "RIFF\u0000\u0000\u0000\u0000WEBPVP8 " "ISO-8859-1"))) "WebP")
  (is (= ::error/not-an-image (reason (.getBytes "hello"))))
  (is (= ::error/not-an-image (reason (byte-array 0))))
  (is (= ::error/not-an-image (reason (java.util.Arrays/copyOf (jpeg 100 100) 200))) "truncated")
  (with-redefs [image/timeout-seconds 0]
    (is (= ::error/too-slow (reason (fixture "rotated-gps.jpg"))) "vips taking too long")))

(deftest large-image-test
  (testing "a large photo comes out at the stored sizes, its shape kept"
    (let [img (java.awt.image.BufferedImage. 6000 4000 java.awt.image.BufferedImage/TYPE_INT_RGB)
          out (java.io.ByteArrayOutputStream.)
          _ (javax.imageio.ImageIO/write img "jpg" out)
          {:keys [full thumb]} (image/process (.toByteArray out))]
      (is (= [1600 1067] [(:width full) (:height full)]))
      (is (= [480 320] [(:width thumb) (:height thumb)])))))

(defn- allocated-bytes
  "Bytes the current thread has allocated so far."
  ^long []
  (.getCurrentThreadAllocatedBytes ^com.sun.management.ThreadMXBean (ManagementFactory/getThreadMXBean)))

(deftest decode-outside-heap-test
  (testing "a 24 megapixel photo is not decoded in the JVM's heap: a few
  megabytes for its bytes in and out, not the ~100 MB of its pixels"
    (let [bs (jpeg 6000 4000)
          before (allocated-bytes)]
      (image/process bs)
      (is (< (- (allocated-bytes) before) (* 16 1024 1024))))))

(deftest busy-test
  (testing "an image waits for its turn a limited time, then is refused as busy"
    (let [^java.util.concurrent.Semaphore slot @#'image/processing]
      (.acquire slot)
      (try
        (with-redefs [image/wait-seconds 0]
          (is (= ::error/images-busy (reason (fixture "rotated-gps.jpg")))))
        (finally
          (.release slot))))))

(deftest decoded-once-test
  (testing "the upload is decoded once: the thumbnail is made from the full size"
    (let [inputs (atom [])
          thumbnail @#'image/thumbnail
          original (fixture "rotated-gps.jpg")]
      (with-redefs [image/thumbnail (fn [bs size] (swap! inputs conj bs) (thumbnail bs size))]
        (let [{:keys [full]} (image/process original)]
          (is (= 1 (count (filter #(identical? original %) @inputs))))
          (is (= [(seq ^bytes (:bytes full))] (map seq (rest @inputs)))))))))

(defn- bytes-of
  "Bytes from unsigned values, padded with zeros to `n`."
  ^bytes [n & values]
  (byte-array (take n (concat (map unchecked-byte values) (repeat 0)))))

(deftest malformed-jpeg-headers-test
  (testing "a segment claiming no length ends the walk: not an image"
    (is (= ::error/not-an-image (reason (bytes-of 64 0xff 0xd8 0xff 0xe0 0x00 0x00)))))
  (testing "a frame header cut short: not an image"
    (is (= ::error/not-an-image (reason (bytes-of 8 0xff 0xd8 0xff 0xc0 0x00 0x11 0x08 0x00)))))
  (testing "fill bytes before a marker are skipped, the frame header still found"
    (is (= ::error/too-many-pixels
           (reason (bytes-of 64 0xff 0xd8 0xff 0xff 0xff 0xc0 0x00 0x11 0x08 0x20 0x00 0x20 0x00)))
        "8192 x 8192, refused before decoding")))
