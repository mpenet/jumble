(ns s-exp.jumble.view.markdown
  "Markdown written by users, as hiccup safe to render: chassis escapes all
  text, raw HTML is shown as text, only web and mail links are kept, images
  and headings are not rendered."
  (:require [clojure.string :as str]
            [nextjournal.markdown :as md]))

(set! *warn-on-reflection* true)

(def ^:private parse-options
  {;; `$20 or $30` is about money, not formulas.
   :disable-inline-formulas true
   :disable-footnotes true
   ;; No #hashtag or [[wiki link]] syntax: those would link to pages that do
   ;; not exist here.
   :text-tokenizers []})

(defn- safe-href
  "`href` when it is an absolute http(s) or mailto URL."
  [href]
  (when (and (string? href) (re-find #"(?i)^(https?://|mailto:)" href))
    href))

(def ^:private renderers
  (merge md/default-hiccup-renderers
         {:doc (partial md/into-hiccup [:div.markdown])
          ;; The page has its own headings; these read as paragraphs.
          :heading (partial md/into-hiccup [:p])
          :html-block (fn [_ node] [:p (md/node->text node)])
          :html-inline (fn [_ node] (md/node->text node))
          ;; Remote images would track readers, and the CSP blocks them anyway.
          :image (fn [_ node] (md/node->text node))
          :link (fn [ctx {:keys [attrs] :as node}]
                  (if-let [href (safe-href (:href attrs))]
                    (md/into-hiccup [:a {:href href :rel "nofollow noopener noreferrer"}] ctx node)
                    (md/into-hiccup [:span] ctx node)))
          ;; Without alignment styles: the CSP refuses inline style attributes.
          :table-header (partial md/into-hiccup [:th])
          :table-data (partial md/into-hiccup [:td])
          ;; The default renders the fence's language into the tag, where
          ;; text like `x#q` sets an id and `a/b` fails to render.
          :code (fn [_ node] [:pre [:code (md/node->text node)]])
          ;; People type descriptions line by line.
          :softbreak (constantly [:br])
          :todo-item (fn [ctx {:keys [attrs] :as node}]
                       (md/into-hiccup [:li [:input {:type "checkbox" :checked (:checked attrs) :disabled true}]]
                                       ctx node))
          :toc (constantly nil)}))

(defn- paragraphs
  "`s` as plain paragraphs, for text the parser cannot handle."
  [s]
  [:div.markdown (for [p (str/split (str/trim s) #"\n\s*\n")] [:p p])])

(defn render
  "Hiccup for the markdown `s`, nil when there is nothing to show. Nesting
  too deep for the parser's recursion (thousands of `>`, say) falls back to
  plain paragraphs rather than failing the page."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (try
      (md/->hiccup renderers (md/parse parse-options s))
      (catch Throwable t
        ;; The parser visits nodes through reflection, which wraps the
        ;; overflow; anything else is a real failure.
        (if (some #(instance? StackOverflowError %) (take-while some? (iterate ex-cause t)))
          (paragraphs s)
          (throw t))))))
