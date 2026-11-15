(ns s-exp.jumble.view.icons
  "Inline SVG icons, decorative: hidden from assistive technology, sized
  and coloured by CSS (currentColor).")

(set! *warn-on-reflection* true)

(defn- icon
  [class & paths]
  (into [:svg {:class (str "icon " class) :viewBox "0 0 24 24" :aria-hidden "true" :focusable "false"
               :fill "none" :stroke "currentColor" :stroke-width "2"
               :stroke-linecap "round" :stroke-linejoin "round"}]
        paths))

(def search (icon "icon-search" [:circle {:cx "11" :cy "11" :r "7"}] [:path {:d "m20 20-3.5-3.5"}]))
(def plus (icon "icon-plus" [:path {:d "M12 5v14M5 12h14"}]))
(def pin (icon "icon-pin" [:path {:d "M12 21s-6-5.3-6-10a6 6 0 0 1 12 0c0 4.7-6 10-6 10z"}] [:circle {:cx "12" :cy "11" :r "2"}]))
(def tag (icon "icon-tag" [:path {:d "M3 12V4h8l9 9-8 8z"}] [:circle {:cx "7.5" :cy "8.5" :r "1"}]))
(def chevron-down (icon "icon-chevron" [:path {:d "m6 9 6 6 6-6"}]))
(def camera (icon "icon-camera" [:path {:d "M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z"}]
                  [:circle {:cx "12" :cy "13.5" :r "3.5"}]))
(def photo (icon "icon-photo" [:rect {:x "3" :y "5" :width "18" :height "14" :rx "2"}]
                 [:circle {:cx "9" :cy "10" :r "1.5"}] [:path {:d "m21 16-5-5-8 8"}]))
(def grid (icon "icon-grid" [:rect {:x "4" :y "4" :width "7" :height "7" :rx "1"}] [:rect {:x "13" :y "4" :width "7" :height "7" :rx "1"}]
                [:rect {:x "4" :y "13" :width "7" :height "7" :rx "1"}] [:rect {:x "13" :y "13" :width "7" :height "7" :rx "1"}]))
(def chat (icon "icon-chat" [:path {:d "M21 12a8 8 0 0 1-11.6 7.1L4 20l1.1-4.6A8 8 0 1 1 21 12z"}]))
(def shield (icon "icon-shield" [:path {:d "M12 3l7 3v5c0 4.5-3 8.3-7 10-4-1.7-7-5.5-7-10V6z"}]))
(def heart (icon "icon-heart" [:path {:d "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"}]))
(def mail (icon "icon-mail" [:rect {:x "3" :y "5" :width "18" :height "14" :rx "2"}] [:path {:d "m3 7 9 6 9-6"}]))
(def user (icon "icon-user" [:circle {:cx "12" :cy "8" :r "4"}] [:path {:d "M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"}]))
(def calendar (icon "icon-calendar" [:rect {:x "3" :y "5" :width "18" :height "16" :rx "2"}] [:path {:d "M3 10h18M8 3v4M16 3v4"}]))
(def empty-box (icon "icon-empty" [:path {:d "M3 8l9-5 9 5v8l-9 5-9-5z"}] [:path {:d "M3 8l9 5 9-5M12 13v8"}]))
(def sun (icon "icon-sun" [:circle {:cx "12" :cy "12" :r "4"}]
               [:path {:d "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"}]))
(def moon (icon "icon-moon" [:path {:d "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"}]))
