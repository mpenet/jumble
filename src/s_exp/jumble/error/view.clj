(ns s-exp.jumble.error.view
  "The error pages: [[error/ex->response]] for each category, a generic page
  inside the site's layout, keeping the signed-in header."
  (:require [s-exp.ex :as ex]
            [s-exp.jumble.web.auth :as auth]
            [s-exp.jumble.error :as error]
            [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.view.layout :as layout]))

(set! *warn-on-reflection* true)

(defn- page
  "The error page of `request`, keeping its header, in the member's
  language: `title` and `text` give its words once that is known."
  [request status title text]
  (auth/in-known-language request
                          #(layout/message %
                                           status
                                           (title)
                                           (text)
                                           [:a.button {:href "/"} (i18n/t "Go to the home page")])))

(defn- failed
  [request]
  (page request 500 #(i18n/t "Something went wrong") #(i18n/t "The problem was logged. Please try again.")))

(defmethod error/ex->response :default [_ request] (failed request))

(defmethod error/ex->response ::ex/fault [_ request] (failed request))

(defmethod error/ex->response ::ex/incorrect [_ request]
  (page request 400 #(i18n/t "Bad request") #(i18n/t "This request could not be understood.")))

(defmethod error/ex->response ::error/too-large [_ request]
  (page request 413 #(i18n/t "Too large") #(i18n/t "That request was larger than allowed.")))

(defmethod error/ex->response ::ex/forbidden [_ request]
  (page request 403 #(i18n/t "Not allowed") #(i18n/t "You are not allowed to do this.")))

(defmethod error/ex->response ::ex/not-found [_ request]
  (auth/in-known-language request layout/not-found))

(defmethod error/ex->response ::ex/unsupported [_ request]
  (page request 405 #(i18n/t "Not supported") #(i18n/t "This cannot be done here.")))

(defmethod error/ex->response ::ex/conflict [_ request]
  (page request
        409
        #(i18n/t "Not possible right now")
        #(i18n/t "This cannot be done as things stand. Reload the page and try again.")))

(defmethod error/ex->response ::ex/busy [_ request]
  (page request 429 #(i18n/t "Too many requests") #(i18n/t "Please wait a moment, then try again.")))

(defmethod error/ex->response ::error/busy [_ request]
  (-> (page request 503 #(i18n/t "Busy right now") #(i18n/t "Please try again in a moment."))
      (assoc-in [:headers "retry-after"] "5")))

(defmethod error/ex->response ::ex/unavailable [_ request]
  (page request 502 #(i18n/t "Unavailable") #(i18n/t "A service this depends on is not answering. Please try again later.")))
