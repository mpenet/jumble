(ns s-exp.jumble.web.server
  "The web server component, serving the application's handler over plain
  HTTP, or over HTTPS with its own certificate when `https` is set."
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [s-exp.enso :as enso]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.listing.choices :as choices]
            [s-exp.jumble.web.https :as https]
            [s-exp.jumble.web.routes :as routes]))

(set! *warn-on-reflection* true)

(def ^:private max-request-body-bytes
  "Room for a new listing: six 10 MB photos (one more than allowed, so the
  form can say so) plus its fields."
  (* 64 1024 1024))

(defrecord Server [host port https settings oidc blobs db server]
  component/Lifecycle
  (start [this]
    ;; A new instance's categories start in its language.
    (binding [i18n/*catalog* (:catalog settings)]
      (choices/localize-defaults! (:datasource db)))
    (let [handler (routes/handler {:db (:datasource db) :oidc oidc :blobs blobs :settings settings})
          server-opts {:max-request-body-bytes max-request-body-bytes}]
      (if https
        (let [served (https/start handler
                                  {:host host :http-port port :base-url (:base-url settings)
                                   :https https :server-opts server-opts})]
          (log/info "Serving HTTP on port" (enso/port (:plain served)) "for the CA's challenges and redirects")
          (assoc this :server served))
        (let [served (enso/run-server handler (assoc server-opts :host host :port port))]
          (log/info "Serving HTTP on port" (enso/port served))
          (assoc this :server served)))))
  (stop [this]
    (when server
      (if https (https/stop server) (enso/stop server)))
    (assoc this :server nil)))

(defn component
  [{:keys! [host port settings blobs] :keys [https oidc]}]
  (map->Server {:host host :port port :https https :settings settings :oidc oidc :blobs blobs}))
