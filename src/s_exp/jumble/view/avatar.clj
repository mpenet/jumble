(ns s-exp.jumble.view.avatar
  "Members' avatars: their initials on one of the community palette's
  colours, chosen by their account so it is the same for them everywhere."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def ^:private colours
  "How many colours avatars take, styled .avatar-0 and on in the
  stylesheet."
  5)

(defn- first-letter
  "The first letter of `word`, whole even outside the Basic Multilingual
  Plane, or nil when it has none."
  [^String word]
  (some #(when (Character/isLetter (int %)) (String. (Character/toChars (int %))))
        (iterator-seq (.iterator (.codePoints word)))))

(defn initials
  "The first letters of the first and last words of `name`, upper case; ?
  when it has no letter."
  [name]
  (let [letters (keep first-letter (str/split (str/trim (or name "")) #"\s+"))]
    (if (seq letters)
      (str/upper-case (str (first letters) (when (next letters) (last letters))))
      "?")))

(defn avatar
  "`member`'s avatar (`:id`, `:name`), decorative: their name is always
  beside it."
  [{:keys [id name]}]
  [:span {:class (str "avatar avatar-" (mod (or id 0) colours)) :aria-hidden "true"} (initials name)])
