(ns s-exp.jumble.view.i18n
  "The text people read, in the instance's language. The English text is
  the key: `(i18n/t \"Post a listing\")`. Translations are gettext .po files
  in resources/i18n, one per language, kept in step with the code by
  dev/i18n.clj; a missing, empty or fuzzy translation shows the English.

  Placeholders are named, `{name}`, filled from a map: with text the result
  is text, with markup (chassis) it is a list to put in the page. In plural
  forms `{n}` is the count."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [s-exp.ex :as ex]
            [s-exp.jumble.error :as error]))

(set! *warn-on-reflection* true)

(def language-names
  "Languages Jumble speaks, in the order they are offered, each named in
  itself, as its speakers look for it: English and those with a catalog."
  [["en" "English"] ["fr" "Français"] ["de" "Deutsch"] ["it" "Italiano"] ["es" "Español"] ["pt" "Português"]
   ["sv" "Svenska"] ["da" "Dansk"] ["nb" "Norsk bokmål"] ["fi" "Suomi"] ["nl" "Nederlands"]])

(def languages
  "The tags of [[language-names]]."
  (set (map first language-names)))

(defn language-name
  "`language`'s name, in itself."
  [language]
  (some (fn [[l name]] (when (= l language) name)) language-names))

(def ^:private plural-rules
  "Index of the plural form for count n, per language, as in their .po
  headers: n > 1 for French, n != 1 for the others (European Portuguese
  among them)."
  {"en" #(if (= 1 %) 0 1)
   "de" #(if (= 1 %) 0 1)
   "it" #(if (= 1 %) 0 1)
   "es" #(if (= 1 %) 0 1)
   "pt" #(if (= 1 %) 0 1)
   "sv" #(if (= 1 %) 0 1)
   "da" #(if (= 1 %) 0 1)
   "nb" #(if (= 1 %) 0 1)
   "fi" #(if (= 1 %) 0 1)
   "nl" #(if (= 1 %) 0 1)
   "fr" #(if (> % 1) 1 0)})

(def ^:dynamic *catalog*
  "The catalog in use (see [[read-catalog]]); nil is English."
  nil)

(def ^:dynamic *community-catalog*
  "The community's catalog, what members read until they choose their own
  language (see [[speaking]]); nil is English."
  nil)

(defn language
  "The language in use, as a tag: en, fr…"
  []
  (:language *catalog* "en"))

;; Reading .po files

(defn- unquote-po
  "The text of a .po string literal, escapes resolved."
  [s]
  (str/replace (subs s 1 (dec (count s)))
               #"\\(.)"
               (fn [[_ c]] (case c "n" "\n" "t" "\t" c))))

(defn- entries
  "The entries of .po `text`: maps of :msgid, :msgid-plural, :msgstr
  (index -> text) and :fuzzy."
  [text]
  (let [flush (fn [acc entry] (cond-> acc (:msgid entry) (conj entry)))]
    (loop [[line & more :as lines] (str/split-lines text)
           entry {}
           field nil
           acc []]
      (if (empty? lines)
        (flush acc entry)
        (let [line (str/trim line)]
          (cond
            (str/blank? line)
            (recur more {} nil (flush acc entry))

            (str/starts-with? line "#,")
            (recur more (cond-> entry (str/includes? line "fuzzy") (assoc :fuzzy true)) field acc)

            (str/starts-with? line "#")
            (recur more entry field acc)

            (str/starts-with? line "\"")
            (recur more (update-in entry field str (unquote-po line)) field acc)

            :else
            (let [[_ k index s] (re-matches #"(msgid_plural|msgid|msgstr)(?:\[(\d+)\])?\s+(\".*\")" line)
                  field (case k
                          "msgid" [:msgid]
                          "msgid_plural" [:msgid-plural]
                          "msgstr" [:msgstr (if index (parse-long index) 0)])]
              (recur more (assoc-in entry field (unquote-po s)) field acc))))))))

(defn read-catalog
  "The catalog of `language` from .po file `source` (anything io/reader
  takes): its translations, keyed by English text, and plural rule."
  [language source]
  (let [rule (or (plural-rules language)
                 (throw (ex/ex-info (str "No plural rule for " language) ::error/no-plural-rule {:language language})))]
    {:language language
     :plural rule
     :messages (into {}
                     (comp (remove :fuzzy)
                           (remove #(= "" (:msgid %)))
                           (map (fn [{:keys [msgid msgstr]}] [msgid msgstr])))
                     (entries (slurp (io/reader source))))}))

(def catalog
  "The bundled catalog of `language`, nil for English; read once."
  (memoize
   (fn [language]
     (when-not (= "en" language)
       (read-catalog language
                     (or (io/resource (str "i18n/" language ".po"))
                         (throw (ex/ex-info (str "No catalog for " language)
                                            ::error/no-catalog
                                            {:language language}))))))))

(defn catalog-of
  "The catalog a member reads in: that of `language`, theirs, or the
  community's when they chose none (nil)."
  [language]
  (if language (catalog language) *community-catalog*))

(defmacro speaking
  "Runs `body` in a member's `language`, the community's when nil: their
  pages, and the emails written to them."
  [language & body]
  `(binding [*catalog* (catalog-of ~language)]
     ~@body))

;; Lookup

(defn- fill
  "`text` with its `{key}` placeholders replaced by `values`: text when
  they are all text, else a list of text and markup."
  [text values]
  (if (empty? values)
    text
    (let [parts (str/split text #"(?=\{[a-z-]+\})|(?<=\{[a-z-]+\})")
          filled (map (fn [part]
                        (if-let [[_ k] (re-matches #"\{([a-z-]+)\}" part)]
                          (get values (keyword k) part)
                          part))
                      parts)]
      (if (every? #(or (string? %) (number? %)) filled)
        (apply str filled)
        (remove #(= "" %) filled)))))

(defn- translated
  "The translation at plural `index` of `msgid`, unless empty."
  [msgid index]
  (not-empty (get-in *catalog* [:messages msgid index])))

(defn t
  "`text`, translated, with `values` filling its placeholders."
  ([text] (t text nil))
  ([text values]
   (fill (or (translated text 0) text) values)))

(defn tn
  "The form of `one`/`other` for count `n`, translated, with `{n}` (the
  count, unless `values` gives it, as markup say) and `values` filling its
  placeholders."
  ([one other n] (tn one other n nil))
  ([one other n values]
   (let [index (if *catalog* ((:plural *catalog*) n) (if (= 1 n) 0 1))]
     (fill (or (translated one index) (if (zero? index) one other))
           (merge {:n n} values)))))
