(ns s-exp.jumble.image
  "Turns uploaded photos (JPEG, PNG, and HEIC or AVIF as phones save them)
  into the JPEGs we store, with libvips' `vips` command. Re-encoding from pixels drops every byte of the original's
  metadata, including GPS position."
  (:require [s-exp.ex :as ex]
            [s-exp.jumble.error :as error])
  (:import (java.io IOException)
           (java.util List)
           (java.util.concurrent Semaphore TimeUnit)))

(set! *warn-on-reflection* true)

(def max-pixels
  "Larger images are refused before decoding: a small compressed file can
  describe gigabytes of pixels."
  40000000)

(def ^:private full-size 1600)
(def ^:private thumb-size 480)
(def ^:private quality 85)
(def ^:private timeout-seconds 60)

(defn- reject
  [t]
  (throw (ex/ex-info (str "Image rejected: " (name t)) t)))

(defn- u8 ^long [^bytes bs ^long i] (bit-and (aget bs i) 0xff))
(defn- u16 ^long [^bytes bs ^long i] (bit-or (bit-shift-left (u8 bs i) 8) (u8 bs (inc i))))
(defn- u32 ^long [^bytes bs ^long i] (bit-or (bit-shift-left (u16 bs i) 16) (u16 bs (+ i 2))))

(defn- starts-with?
  [^bytes bs offset prefix]
  (and (<= (+ (long offset) (count prefix)) (alength bs))
       (every? (fn [[i b]] (= (u8 bs (+ (long offset) (long i))) b))
               (map-indexed vector prefix))))

(def ^:private formats
  "What phones and computers save photos as, and we take."
  #{"jpeg" "png" "heic" "avif"})

(def ^:private png-signature [0x89 0x50 0x4e 0x47 0x0d 0x0a 0x1a 0x0a])

(defn- png-size
  "Dimensions from a PNG's IHDR chunk, which comes first."
  [^bytes bs]
  (when (and (>= (alength bs) 24) (starts-with? bs 12 (map int "IHDR")))
    {:width (u32 bs 16) :height (u32 bs 20)}))

(def ^:private frame-markers
  "JPEG start-of-frame markers, holding the dimensions."
  #{0xc0 0xc1 0xc2 0xc3 0xc5 0xc6 0xc7 0xc9 0xca 0xcb 0xcd 0xce 0xcf})

(defn- jpeg-size
  "Dimensions from a JPEG's start-of-frame segment, found by walking the
  segments before it."
  [^bytes bs]
  (loop [i 2]
    (when (< (+ i 8) (alength bs))
      (let [marker (u8 bs (inc i))]
        (cond
          (not= 0xff (u8 bs i)) nil
          ;; Fill bytes before a marker.
          (= 0xff marker) (recur (inc i))
          (frame-markers marker) {:width (u16 bs (+ i 7)) :height (u16 bs (+ i 5))}
          ;; Markers standing alone, without a length.
          (or (<= 0xd0 marker 0xd7) (= 0x01 marker)) (recur (+ i 2))
          :else (recur (+ i 2 (u16 bs (+ i 2)))))))))

(defn- boxes
  "The ISO base media file format boxes (HEIF's) between `start` and `end`:
  `{:type :from :to}`, their contents' bounds; none past a box whose size
  does not fit."
  [^bytes bs start end]
  (loop [i (long start) found []]
    (if (> (+ i 8) (long end))
      found
      (let [size (u32 bs i)
            [header size] (cond
                            (= 1 size)
                            (if (and (<= (+ i 16) (long end)) (zero? (u32 bs (+ i 8))))
                              [16 (u32 bs (+ i 12))]
                              [16 -1])

                            (zero? size) [8 (- (long end) i)]
                            :else [8 size])
            to (+ i (long size))]
        (if (or (< (long size) (long header)) (> to (long end)))
          found
          (recur to
                 (conj found
                       {:type (String. bs (int (+ i 4)) 4 "ISO-8859-1")
                        :from (+ i (long header)) :to to})))))))

(defn- children
  "The boxes inside box `b`; `full`, a full box, has its version and flags
  first."
  [bs {:keys [from to]} full]
  (boxes bs (+ (long from) (if full 4 0)) to))

(defn- heif-size
  "The largest image size a HEIF's header declares (its `ispe` properties,
  the primary image's and its tiles' or thumbnails'), nil when it declares
  none: what the pixel limit is checked against, before decoding."
  [^bytes bs]
  (let [sizes (for [meta (boxes bs 0 (alength bs)) :when (= "meta" (:type meta))
                    iprp (children bs meta true) :when (= "iprp" (:type iprp))
                    ipco (children bs iprp false) :when (= "ipco" (:type ipco))
                    ispe (children bs ipco false) :when (and (= "ispe" (:type ispe))
                                                             (<= (+ (long (:from ispe)) 12) (long (:to ispe))))]
                {:width (u32 bs (+ (long (:from ispe)) 4)) :height (u32 bs (+ (long (:from ispe)) 8))})]
    (when (seq sizes)
      (apply max-key #(* (long (:width %)) (long (:height %))) sizes))))

(defn- heif-format
  "\"heic\" or \"avif\" for a HEIF file, by the brands its `ftyp` box
  lists, nil for another kind."
  [^bytes bs]
  (when-let [{:keys [from to]} (first (filter #(= "ftyp" (:type %)) (take 1 (boxes bs 0 (alength bs)))))]
    (let [brands (set (for [i (range from (- (long to) 3) 4) :when (not= i (+ (long from) 4))]
                        (String. bs (int i) 4 "ISO-8859-1")))]
      (cond
        (some brands ["avif" "avis"]) "avif"
        (some brands ["heic" "heix" "heim" "heis" "hevc" "hevx" "mif1" "msf1"]) "heic"))))

(defn- probe
  "Format and dimensions from the image header, without decoding pixels.
  nil when the bytes are no image we recognise."
  [^bytes bs]
  (cond
    (starts-with? bs 0 png-signature) (some-> (png-size bs) (assoc :format "png"))
    (starts-with? bs 0 [0xff 0xd8 0xff]) (some-> (jpeg-size bs) (assoc :format "jpeg"))
    ;; Recognised, to be refused as such.
    (starts-with? bs 0 (map int "GIF8")) {:format "gif"}
    (and (starts-with? bs 0 (map int "RIFF")) (starts-with? bs 8 (map int "WEBP"))) {:format "webp"}

    (starts-with? bs 4 (map int "ftyp"))
    (when-let [format (heif-format bs)]
      (assoc (or (heif-size bs) (reject ::error/not-an-image)) :format format))))

(defn- thumbnail-command
  "Reads an image on standard input and writes it as a JPEG without
  metadata on standard output: rotated/flipped as its EXIF orientation
  says, at most `size` pixels on its longest edge (never enlarged),
  transparency on white. JPEGs are decoded already scaled down."
  [size]
  ["vips" "thumbnail_source" "[descriptor=0]"
   (str ".jpg[Q=" quality ",keep=none,background=255]")
   (str size) "--size" "down" "--fail-on" "truncated"])

(defn- thumbnail
  "`bs` as a JPEG at most `size` pixels on its longest edge:
  `{:bytes :width :height}`."
  [^bytes bs size]
  (let [process (.start (doto (ProcessBuilder. ^List (thumbnail-command size))
                          (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
                          ;; Only loaders safe for untrusted input.
                          (-> .environment (.put "VIPS_BLOCK_UNTRUSTED" "1"))))
        out (future (with-open [in (.getInputStream process)]
                      (.readAllBytes in)))]
    (future
      (try
        (with-open [in (.getOutputStream process)]
          (.write in bs))
        ;; vips stops reading input it cannot decode.
        (catch IOException _)))
    (when-not (.waitFor process timeout-seconds TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (ex/ex-info (str "vips took over " timeout-seconds " seconds") ::error/too-slow)))
    (when-not (zero? (.exitValue process))
      (reject ::error/not-an-image))
    (let [^bytes jpeg @out]
      (assoc (or (jpeg-size jpeg) (reject ::error/not-an-image)) :bytes jpeg))))

(def ^:private processing
  "One image processed at a time: each keeps a core or more busy, and
  uploads run on as many virtual threads as there are requests. Others
  wait their turn."
  (Semaphore. 1 true))

(def ^:private wait-seconds
  "How long an image waits for its turn before it is refused as busy: a
  queue of slow images must not hold every upload, and its bytes, forever."
  30)

(defn- with-processing-slot
  [f]
  (if (.tryAcquire ^Semaphore processing wait-seconds TimeUnit/SECONDS)
    (try
      (f)
      (finally
        (.release ^Semaphore processing)))
    (throw (ex/ex-info (str "No turn to process an image within " wait-seconds " seconds") ::error/images-busy))))

(defn process
  "The stored versions of an uploaded image: `{:full {:bytes :width :height}
  :thumb {...}}`, JPEGs at most 1600 and 480 pixels on their longest edge.
  Throws ex-info of type ::error/not-an-image, ::error/unsupported-format
  (only JPEG, PNG, HEIC and AVIF are accepted), ::error/too-many-pixels (over
  [[max-pixels]]), ::error/too-slow (vips over [[timeout-seconds]]) or
  ::error/images-busy (no turn within [[wait-seconds]])."
  [^bytes bs]
  (let [{:keys [format width height]} (or (probe bs) (reject ::error/not-an-image))]
    (when-not (formats format)
      (reject ::error/unsupported-format))
    (when (> (* (long width) (long height)) max-pixels)
      (reject ::error/too-many-pixels))
    (with-processing-slot
      ;; The thumbnail from the full size, already turned upright, stripped
      ;; and on white: the upload is decoded once.
      #(let [full (thumbnail bs full-size)]
         {:full full
          :thumb (thumbnail (:bytes full) thumb-size)}))))
