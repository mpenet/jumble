(ns s-exp.jumble.page.palette
  "The community's colour palette, chosen by its moderators: one of those in
  resources/public/app.css, forest until one is chosen."
  (:require [next.jdbc :as jdbc]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.view.i18n :as i18n]))

(set! *warn-on-reflection* true)

(def names
  "The palettes, in the order they are offered."
  ["forest" "plum" "ink" "slate" "tokyo" "catppuccin" "gruvbox" "nord" "rose-pine"
   "solarized" "dracula" "one" "github" "everforest" "kanagawa"])

(defn label
  "The name people read: a colour, translated, or an editor theme's own."
  [palette]
  (case palette
    "forest" (i18n/t "Forest")
    "plum" (i18n/t "Plum")
    "ink" (i18n/t "Ink")
    "slate" (i18n/t "Slate")
    "tokyo" "Tokyo Night"
    "catppuccin" "Catppuccin"
    "gruvbox" "Gruvbox"
    "nord" "Nord"
    "rose-pine" "Rosé Pine"
    "solarized" "Solarized"
    "dracula" "Dracula"
    "one" "One"
    "github" "GitHub"
    "everforest" "Everforest"
    "kanagawa" "Kanagawa"))

(defn chosen
  [db]
  (or (:value (jdbc/execute-one! db ["SELECT value FROM community_setting WHERE name = 'palette'"] db/opts))
      "forest"))

(defn choose!
  "Makes `palette`, one of [[names]], the community's."
  [db palette]
  (jdbc/execute-one! db
                     ["INSERT INTO community_setting (name, value) VALUES ('palette', ?)
                          ON CONFLICT (name) DO UPDATE SET value = excluded.value"
                      palette]))

(defn wrap-palette
  "Gives the request the community's `:palette`, a delay: read only by
  pages, which show it."
  [handler]
  (fn [{:keys [ctx] :as request}]
    (handler (assoc request :palette (delay (chosen (:db ctx)))))))
