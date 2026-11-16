(ns s-exp.jumble.page.assets
  "Static files served at content-hashed addresses, cached for good by
  browsers."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [s-exp.ex :as ex]
            [s-exp.jumble.error :as error]
            [s-exp.jumble.token :as token]
            [s-exp.jumble.web.response :as response]))

(set! *warn-on-reflection* true)

(def ^:private files
  "Files under resources/public that are served, with their content type.
  Nothing else is reachable."
  {"datastar.js" "text/javascript; charset=utf-8"
   "push.js" "text/javascript; charset=utf-8"
   "app.css" "text/css; charset=utf-8"
   "favicon.png" "image/png"
   "apple-touch-icon.png" "image/png"
   "icon-192.png" "image/png"
   "icon-512.png" "image/png"
   "logo.png" "image/png"
   "logo-white.png" "image/png"
   "mark.png" "image/png"})

(defn- content-hash
  [^bytes bs]
  (subs (token/sha256-hex bs) 0 10))

(defn- load-asset
  [[file content-type]]
  (let [bs (with-open [in (io/input-stream (or (io/resource (str "public/" file))
                                               (throw (ex/ex-info "Missing asset" ::error/missing-asset {:file file}))))]
             (.readAllBytes in))
        dot (str/last-index-of file ".")
        hashed (str (subs file 0 dot) "." (content-hash bs) (subs file dot))]
    [file {:file hashed :bytes bs :content-type content-type}]))

(def ^:private assets
  (delay (into {} (map load-asset) files)))

(def ^:private by-hashed-name
  (delay (into {} (map (fn [[_ asset]] [(:file asset) asset])) @assets)))

(defn url
  "Path of `file`, carrying its content hash: browsers may cache it forever,
  and a change to the file changes the URL."
  [file]
  (str "/assets/"
       (:file (or (get @assets file)
                  (throw (ex/ex-info "Unknown asset" ::error/unknown-asset {:file file}))))))

(defn serve
  [{:keys [path-params]}]
  (if-let [{:keys [bytes content-type]} (get @by-hashed-name (:file path-params))]
    {:status 200
     :headers {"content-type" content-type
               "cache-control" "public, max-age=31536000, immutable"}
     :body bytes}
    (response/text 404 "Not found")))

(def routes
  {[:get "/assets/{file}"] #'serve})
