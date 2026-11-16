(ns s-exp.jumble.page.manifest
  "GET /manifest.webmanifest: what a phone needs to add the community to its
  home screen, as an app of its own: its name, where it opens, its icon."
  (:require [babashka.json :as json]
            [s-exp.jumble.page.assets :as assets]))

(set! *warn-on-reflection* true)

(defn- icon
  [file size]
  {:src (assets/url file) :sizes (str size "x" size) :type "image/png"})

(defn manifest
  [{:keys [ctx]}]
  {:status 200
   :headers {"content-type" "application/manifest+json"}
   :body (json/write-str {:name (get-in ctx [:settings :app-name])
                          :start_url "/"
                          :display "standalone"
                          ;; The page's light background, behind the icon while it opens.
                          :background_color "#f6f8fa"
                          :icons [(icon "icon-192.png" 192) (icon "icon-512.png" 512)]})})

(def routes
  {[:get "/manifest.webmanifest"] #'manifest})
