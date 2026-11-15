(ns s-exp.jumble.page.health
  "GET /healthz: whether the app and its database answer."
  (:require [clojure.tools.logging :as log]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.web.response :as response]))

(set! *warn-on-reflection* true)

(defn check
  [{:keys [ctx]}]
  (try
    (db/reachable? (:db ctx))
    (response/text 200 "ok")
    (catch Exception e
      (log/warn e "Health check failed")
      (response/text 503 "unavailable"))))

(def routes
  {[:get "/healthz"] #'check})
