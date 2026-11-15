(ns s-exp.jumble.home.view
  "The home page: what Jumble is for here, and the latest listings."
  (:require [s-exp.jumble.view.i18n :as i18n]
            [s-exp.jumble.view.icons :as icons]
            [s-exp.jumble.view.layout :as layout]
            [s-exp.jumble.listing.view :as listing-view]))

(set! *warn-on-reflection* true)

(defn- hero
  [active]
  [:section.hero
   [:h1.hero-title (i18n/t "Give your things a {second-life} in your community."
                           {:second-life [:span.highlight (i18n/t "second life")]})]
   [:p.hero-lede (i18n/t "Sell, give away or lend what you no longer need to people around you, and find what you were looking for.")]
   [:form.hero-search {:method "get" :action "/listings" :role "search"}
    [:div.search-field
     icons/search
     [:input {:type "search" :name "q" :maxlength 100
              :placeholder (i18n/t "A bike, a desk lamp, a stroller…") :aria-label (i18n/t "Search listings")}]]
    [:button {:type "submit"} (i18n/t "Search")]]
   [:p.hero-count (i18n/tn "{n} thing up for grabs right now" "{n} things up for grabs right now" active
                           {:n [:strong active]})]
   [:div.hero-actions
    [:a.button.button-pop.button-large {:href "/listings/new"} icons/plus (i18n/t "Post a listing")]
    [:a.hero-browse {:href "/listings"} (i18n/t "or browse all listings →")]]])

(defn- just-posted
  [env listings]
  (when (seq listings)
    [:section.home-section
     [:div.section-head
      [:h2 (i18n/t "Just posted")]
      [:a.section-link {:href "/listings"} (i18n/t "See all →")]]
     [:div.cards (for [listing listings] (listing-view/card env listing :h3))]]))

(defn index
  [request active latest photo-url]
  (layout/page
   request {}
   (hero active)
   (just-posted (listing-view/cards-env request photo-url "/") latest)))
