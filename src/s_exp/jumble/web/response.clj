(ns s-exp.jumble.web.response
  "Ring responses shared by every context.")

(set! *warn-on-reflection* true)

(defn html
  [status body]
  {:status status
   :headers {"content-type" "text/html; charset=utf-8"}
   :body body})

(defn text
  [status body]
  {:status status
   :headers {"content-type" "text/plain; charset=utf-8"}
   :body body})

(defn redirect
  "303 See Other by default: the answer to a form post."
  ([location] (redirect 303 location))
  ([status location]
   {:status status :headers {"location" location} :body ""}))

(defn datastar?
  "Whether `request` was sent by Datastar rather than as a page load."
  [request]
  (= "true" (get-in request [:headers "datastar-request"])))
