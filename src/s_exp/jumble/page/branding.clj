(ns s-exp.jumble.page.branding
  "A community's own logo in the header: an SVG or PNG file named in the
  configuration, read once at startup and served at a content-hashed address."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [s-exp.ex :as ex]
            [s-exp.jumble.error :as error]
            [s-exp.jumble.token :as token]
            [s-exp.jumble.web.response :as response])
  (:import (java.io File)
           (java.nio.charset StandardCharsets)
           (java.util Arrays)))

(set! *warn-on-reflection* true)

(def ^:private max-bytes (* 200 1024))

(def ^:private png-signature
  (byte-array [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A]))

(defn- png?
  [^bytes bs]
  (and (>= (alength bs) 8)
       (Arrays/equals (Arrays/copyOf bs 8) ^bytes png-signature)))

(defn- svg?
  "Whether `bs` is an SVG document: XML whose root element is `svg`."
  [^bytes bs]
  (let [head (String. bs 0 (min (alength bs) 4096) StandardCharsets/UTF_8)]
    (boolean (re-find #"(?s)^\s*(<\?xml[^>]*>\s*)?(<!--.*?-->\s*)*(<!DOCTYPE[^>]*>\s*)?<svg[\s>]" head))))

(defn- refuse
  [path message]
  (throw (ex/ex-info (str "Logo " path ": " message) ::error/invalid-logo {:path path})))

(defn read-logo
  "The logo at `path`: `{:file :content-type :bytes}`, `:file` naming it by
  its content's hash. Throws ex-info of type ::error/invalid-logo unless it
  is an SVG or PNG of at most 200 KB, judged by its content."
  [path]
  (let [f (io/file path)]
    (when-not (.isFile ^File f)
      (refuse path "not found"))
    (when (> (.length ^File f) max-bytes)
      (refuse path "must be at most 200 KB"))
    (let [bs (with-open [in (io/input-stream f)]
               (.readAllBytes in))
          [ext content-type] (cond
                               (png? bs) ["png" "image/png"]
                               (svg? bs) ["svg" "image/svg+xml"]
                               :else (refuse path "must be an SVG or PNG image"))
          hash (subs (token/sha256-hex bs) 0 10)]
      {:file (str "logo." hash "." ext) :content-type content-type :bytes bs})))

(defn url
  [logo]
  (str "/brand/" (:file logo)))

(def ^:private svg-policy
  "For an SVG opened on its own, as a document: no scripts, nothing loaded."
  "default-src 'none'; style-src 'unsafe-inline'; script-src 'none'; sandbox")

(defn serve
  [{:keys [ctx path-params]}]
  (let [{:keys [logo logo-dark]} (:settings ctx)
        file (:file path-params)]
    (if-let [{:keys [bytes content-type]} (first (filter #(= file (:file %)) (remove nil? [logo logo-dark])))]
      {:status 200
       :headers (cond-> {"content-type" content-type
                         "cache-control" "public, max-age=31536000, immutable"}
                  (str/starts-with? content-type "image/svg") (assoc "content-security-policy" svg-policy))
       :body bytes}
      (response/text 404 "Not found"))))

(def routes
  {[:get "/brand/{file}"] #'serve})
