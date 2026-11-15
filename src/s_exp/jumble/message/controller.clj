(ns s-exp.jumble.message.controller
  "Conversations on the web: writing about a listing, the inbox, a thread,
  replying."
  (:require [s-exp.jumble.metrics :as metrics]
            [s-exp.jumble.web.auth :as auth]
            [s-exp.jumble.view.layout :as layout]
            [s-exp.jumble.listing.model :as listing]
            [s-exp.jumble.message.model :as message]
            [s-exp.jumble.message.view :as view]
            [s-exp.jumble.web.params :as params]
            [s-exp.jumble.web.response :as response]))

(set! *warn-on-reflection* true)

(def ^:private messages-sent
  (metrics/counter "jumble_messages_sent_total" "Messages sent, by kind: first (a conversation opened) or reply."
                   [{:kind "first"} {:kind "reply"}]))

(defn- messageable
  "The listing in the path when the user can write to its owner about it:
  someone else's, still on offer."
  [{:keys [ctx path-params user]}]
  (when-let [found (some->> (params/id (:id path-params)) (listing/by-id (:db ctx)))]
    (when (and (listing/on-board (:status found)) (not= (:user-id found) (:id user)))
      found)))

(defn compose
  "The first-message form, or the existing thread with the owner about it."
  [{:keys [ctx user] :as request}]
  (if-let [found (messageable request)]
    (if-let [thread-id (message/thread-about (:db ctx) (:id found) (:id user) (:user-id found))]
      (response/redirect 302 (view/thread-path thread-id))
      (response/html 200 (view/compose request found {:values {:subject (:title found)}})))
    (layout/not-found request)))

(defn send-first
  [{:keys [ctx params user] :as request}]
  (if-let [found (messageable request)]
    (let [values {:subject (params/one params "subject") :body (params/one params "body")}
          {:keys [errors] {:keys [subject body]} :message} (message/validate values)]
      (if errors
        (response/html 422 (view/compose request found {:values values :errors errors}))
        (if-let [thread-id (message/start! (:db ctx) {:sender-id (:id user) :listing-id (:id found)
                                                      :subject subject :body body
                                                      :now (System/currentTimeMillis)})]
          (do (metrics/inc! messages-sent {:kind "first"})
              (response/redirect (view/thread-path thread-id)))
          (layout/not-found request))))
    (layout/not-found request)))

(defn inbox
  [{:keys [ctx user] :as request}]
  (response/html 200 (view/inbox request (message/inbox (:db ctx) (:id user)))))

(defn- thread-of
  "The thread in the path as the user sees it, or nil."
  [{:keys [ctx path-params user]}]
  (when-let [thread-id (params/id (:id path-params))]
    (message/thread (:db ctx) thread-id (:id user))))

(defn show
  "The thread, marked read up to the messages shown."
  [{:keys [ctx user] :as request}]
  (if-let [{:keys [id messages] :as found} (thread-of request)]
    (do (message/mark-read! (:db ctx) id (:id user) (:id (peek messages)))
        (response/html 200 (view/thread request found {})))
    (layout/not-found request)))

(defn reply
  [{:keys [ctx params path-params user] :as request}]
  (let [{:keys [errors body]} (message/validate-body (params/one params "body"))]
    (if errors
      (if-let [found (thread-of request)]
        (response/html 422 (view/thread request found {:values {:body (params/one params "body")} :errors errors}))
        (layout/not-found request))
      (let [thread-id (params/id (:id path-params))]
        (if-let [message-id (when thread-id
                              (message/reply! (:db ctx) thread-id (:id user) body (System/currentTimeMillis)))]
          (do (metrics/inc! messages-sent {:kind "reply"})
              (response/redirect (str (view/thread-path thread-id) "#m" message-id)))
          (layout/not-found request))))))

(def routes
  {[:get "/listings/{id}/message"] (auth/require-user #'compose)
   [:post "/listings/{id}/message"] (auth/require-user #'send-first)
   [:get "/messages"] (auth/require-user #'inbox)
   [:get "/messages/{id}"] (auth/require-user #'show)
   [:post "/messages/{id}"] (auth/require-user #'reply)})
