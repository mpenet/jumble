(ns s-exp.jumble.home.controller
  "The home page."
  (:require [s-exp.jumble.web.auth :as auth]
            [s-exp.jumble.home.view :as view]
            [s-exp.jumble.listing.controller :refer [mark-favorites wrap-choices]]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.web.response :as response])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def ^:private latest-count 4)

(defn index
  [{:keys [ctx user] :as request}]
  (let [{:keys [db blobs]} ctx]
    (response/html 200
                   (view/index request
                               ;; Things on offer: not what people are looking for.
                               (let [{:keys [total kinds]} (listing/facets db {})]
                                 (- total (get kinds "wanted" 0)))
                               (mark-favorites db user (:listings (listing/board db {:limit latest-count})))
                               (listing/photo-urls blobs (Instant/now))))))

(def routes
  {[:get "/"] (auth/require-user (wrap-choices #'index))})
