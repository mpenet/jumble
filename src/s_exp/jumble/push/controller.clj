(ns s-exp.jumble.push.controller
  "Turning browser notifications on and off for a device, and the service
  worker that shows them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [s-exp.jumble.push.model :as push]
            [s-exp.jumble.web.auth :as auth]
            [s-exp.jumble.web.response :as response]
            [s-exp.oda :as oda]))

(set! *warn-on-reflection* true)

(def ^:private service-worker
  (delay (slurp (io/resource "public/sw.js"))))

(defn- serve-worker
  "The service worker, at the root so that it covers every page; browsers
  check it for updates."
  [_]
  {:status 200
   :headers {"content-type" "text/javascript; charset=utf-8"
             "cache-control" "no-cache"}
   :body @service-worker})

(defn- json?
  "Whether the request says it sends JSON: other bodies, multipart ones
  especially, are not read here (and escape the form size limit)."
  [{:keys [headers]}]
  (str/starts-with? (get headers "content-type" "") "application/json"))

(defn- json-body
  "The JSON object sent, nil when it is not one."
  [{:keys [body]}]
  (let [v (try (oda/parse body) (catch Exception _ nil))]
    (when (map? v) v)))

(defn- wrap-json
  "Answers 415 to a body that is not JSON."
  [handler]
  (fn [request]
    (if (json? request)
      (handler request)
      (response/text 415 "Expected JSON"))))

(defn- subscribe
  "Keeps the subscription of the member's browser on this device, as
  PushSubscription.toJSON() gives it, while they stay signed in there."
  [{:keys [ctx user] :as request}]
  (if-let [{:strs [endpoint keys]} (json-body request)]
    (if (push/subscribe! (:db ctx) (:id user) (auth/session-token request)
                         {:endpoint endpoint :p256dh (get keys "p256dh") :auth (get keys "auth")}
                         (System/currentTimeMillis))
      {:status 204}
      (response/text 422 "Not a push subscription"))
    (response/text 400 "Expected a JSON object")))

(defn- unsubscribe
  "Turns notifications off on the device subscribed with the endpoint sent."
  [{:keys [ctx user] :as request}]
  (if-let [{:strs [endpoint]} (json-body request)]
    (do (when (string? endpoint)
          (push/unsubscribe! (:db ctx) (:id user) endpoint))
        {:status 204})
    (response/text 400 "Expected a JSON object")))

(defn- remove-device
  "Turns notifications off on one of the member's devices, from the list."
  [{:keys [ctx user path-params]}]
  (when-let [id (parse-long (:id path-params))]
    (push/remove! (:db ctx) (:id user) id))
  (response/redirect "/settings"))

(def routes
  {[:get "/sw.js"] #'serve-worker
   [:post "/push/subscriptions"] (auth/require-user (wrap-json #'subscribe))
   [:post "/push/subscriptions/delete"] (auth/require-user (wrap-json #'unsubscribe))
   [:post "/settings/devices/{id}/delete"] (auth/require-user #'remove-device)})
