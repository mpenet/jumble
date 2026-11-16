(ns i18n-catalogs
  "Keeps the translation catalogs in step with the code: collects the text
  of every i18n/t and i18n/tn call in src, writes it as the template
  resources/i18n/jumble.pot, and merges it into each language's .po,
  keeping translations, adding new text untranslated, dropping text the
  code no longer has.

    clojure -M:dev -m i18n-catalogs"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [s-exp.jumble.view.i18n :as i18n])
  (:import (clojure.lang LineNumberingPushbackReader)
           (java.io File)))

(set! *warn-on-reflection* true)

(defn- source-files
  []
  (->> (file-seq (io/file "src"))
       (filter #(str/ends-with? (.getName ^File %) ".clj"))
       (sort-by str)))

(defn- forms
  "The forms of `file`, read in its namespace so aliases resolve."
  [^File file]
  (with-open [reader (LineNumberingPushbackReader. (io/reader file))]
    (let [[_ ns-name] (read reader)]
      (require ns-name)
      (binding [*ns* (the-ns ns-name)]
        (doall (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof :read-cond :allow} reader))))))))

(defn- calls
  "The translation calls in `form`, anywhere in it."
  [form]
  (filter #(and (seq? %) ('#{i18n/t i18n/tn} (first %)))
          (tree-seq coll? seq form)))

(defn messages
  "The text to translate in src, in order of first use: maps of :msgid,
  :msgid-plural for plurals, and :refs (files)."
  []
  (let [found (for [^File file (source-files)
                    form (forms file)
                    [f & args] (calls form)
                    :let [[msgid plural] args]]
                (do (when-not (and (string? msgid) (or (= 'i18n/t f) (string? plural)))
                      (throw (ex-info (str "Text to translate must be literal strings, in " file)
                                      {:file (str file) :call (cons f args)})))
                    {:msgid msgid
                     :msgid-plural (when (= 'i18n/tn f) plural)
                     :ref (str/replace (str file) File/separator "/")}))]
    (->> found
         (reduce (fn [acc {:keys [msgid ref] :as m}]
                   (if-let [i (get-in acc [:index msgid])]
                     (update-in acc [:entries i :refs] #(if (some #{ref} %) % (conj % ref)))
                     (-> acc
                         (assoc-in [:index msgid] (count (:entries acc)))
                         (update :entries conj (-> m (dissoc :ref) (assoc :refs [ref]))))))
                 {:index {} :entries []})
         :entries)))

(defn- quote-po
  [s]
  (str "\"" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"") (str/replace "\n" "\\n")) "\""))

(defn- po-entry
  [{:keys [msgid msgid-plural refs]} translation]
  (str/join "\n"
            (concat [(str "#: " (str/join " " refs))
                     (str "msgid " (quote-po msgid))]
                    (if msgid-plural
                      (cons (str "msgid_plural " (quote-po msgid-plural))
                            (for [i [0 1]] (str "msgstr[" i "] " (quote-po (get translation i "")))))
                      [(str "msgstr " (quote-po (get translation 0 "")))]))))

(def ^:private plural-forms
  {"fr" "nplurals=2; plural=(n > 1);"
   "de" "nplurals=2; plural=(n != 1);"
   "it" "nplurals=2; plural=(n != 1);"
   "es" "nplurals=2; plural=(n != 1);"
   "pt" "nplurals=2; plural=(n != 1);"
   "sv" "nplurals=2; plural=(n != 1);"
   "da" "nplurals=2; plural=(n != 1);"
   "nb" "nplurals=2; plural=(n != 1);"
   "fi" "nplurals=2; plural=(n != 1);"
   "nl" "nplurals=2; plural=(n != 1);"})

(defn- header
  [language]
  (str "msgid \"\"\nmsgstr \"\"\n"
       (when language (str "\"Language: " language "\\n\"\n"))
       "\"Content-Type: text/plain; charset=UTF-8\\n\"\n"
       "\"Plural-Forms: "
       (plural-forms language "nplurals=2; plural=(n != 1);")
       "\\n\"\n"))

(defn catalog-text
  "The .po text of `language` (nil for the template) for `messages`,
  with `translations` (msgid -> index -> text) where known."
  [language messages translations]
  (str (header language)
       "\n"
       (str/join "\n\n" (map #(po-entry % (get translations (:msgid %))) messages))
       "\n"))

(defn translations
  "msgid -> index -> text of the .po file of `language` (fuzzy ones are
  dropped, as when reading), empty when there is none."
  [language]
  (let [file (io/file "resources/i18n" (str language ".po"))]
    (if (.exists file)
      (:messages (i18n/read-catalog language file))
      {})))

(defn -main
  [& _]
  (let [found (messages)]
    (spit "resources/i18n/jumble.pot" (catalog-text nil found {}))
    (doseq [language (sort (disj i18n/languages "en"))]
      (spit (str "resources/i18n/" language ".po") (catalog-text language found (translations language))))
    (println (count found) "messages")))
