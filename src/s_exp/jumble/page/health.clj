(ns s-exp.jumble.page.health
  "GET /healthz: whether the app and its database answer, and the version
  running."
  (:require [clojure.tools.logging :as log]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.version :as version]
            [s-exp.jumble.web.response :as response]))

(set! *warn-on-reflection* true)

(defn check
  [{:keys [ctx]}]
  (try
    (db/reachable? (:db ctx))
    ;; ok first: uptime monitors look for it.
    (let [{v :version :keys [revision]} @version/info]
      (response/text 200 (str "ok\nversion " v "\nrevision " revision "\n")))
    (catch Exception e
      (log/warn e "Health check failed")
      (response/text 503 "unavailable"))))

(def routes
  {[:get "/healthz"] #'check})
