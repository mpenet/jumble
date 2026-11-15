(ns s-exp.jumble.account.membership
  "Who may sign in, by email address: anyone (`*`), or a list of patterns,
  each an address (`bob@example.org`), a domain (`*@example.org`) or a
  domain's subdomains (`*@*.example.org`). Case is ignored."
  (:require [clojure.string :as str]
            [s-exp.ex :as ex]
            [s-exp.jumble.error :as error]
            [s-exp.jumble.view.i18n :as i18n]))

(set! *warn-on-reflection* true)

(def ^:private local-part
  "A local part, without wildcards."
  "[^@\\s*]+")

(def ^:private domain
  "A domain of two labels or more: not a whole top-level domain, not a host."
  "[^@\\s*.]+(?:\\.[^@\\s*.]+)+")

(def ^:private forms
  "The accepted pattern shapes: no other placement of `*`."
  (re-pattern (str "(?:\\*|" local-part ")@(?:\\*\\.)?" domain)))

(defn- invalid
  [pattern]
  (throw (ex/ex-info (i18n/t "Not an email pattern: {pattern} (use *, name@domain, *@domain or *@*.domain)"
                             {:pattern (pr-str pattern)})
                     ::error/invalid-pattern
                     {:pattern pattern})))

(defn parse
  "The rule in `patterns`, comma separated text or a list of strings: `:anyone`
  when one of them is `*`, else the patterns, lowercased. Throws ex-info of
  type ::error/invalid-pattern for a malformed pattern or none at all."
  [patterns]
  (let [patterns (->> (if (string? patterns) (str/split patterns #",") patterns)
                      (map (comp str/lower-case str/trim))
                      (remove str/blank?)
                      vec)]
    (when (empty? patterns)
      (invalid ""))
    (if (some #{"*"} patterns)
      :anyone
      (do (doseq [p patterns]
            (when-not (re-matches forms p)
              (invalid p)))
          patterns))))

(defn- matches?
  [^String pattern ^String email]
  (let [[local domain] (str/split pattern #"@" 2)
        at (.lastIndexOf email "@")
        email-local (subs email 0 at)
        email-domain (subs email (inc at))]
    (and (or (= "*" local) (= local email-local))
         (if (str/starts-with? domain "*.")
           (str/ends-with? email-domain (subs domain 1))
           (= domain email-domain)))))

(defn only-by-address?
  "Whether `rule` lets `email` in by its exact address alone, no wildcard
  pattern matching it too."
  [rule email]
  (let [email (str/lower-case email)]
    (and (not= :anyone rule)
         (str/includes? email "@")
         (boolean (some #{email} rule))
         (not-any? #(and (str/includes? % "*") (matches? % email)) rule))))

(defn allows?
  "Whether `rule` (from [[parse]]) lets `email` in."
  [rule email]
  (or (= :anyone rule)
      (let [email (str/lower-case email)]
        (and (str/includes? email "@")
             (boolean (some #(matches? % email) rule))))))
