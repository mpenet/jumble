(ns s-exp.jumble.store.switch
  "The community's on/off choices, each a row of community_setting by name:
  off until turned on."
  (:require [next.jdbc :as jdbc]
            [s-exp.jumble.store.db :as db]))

(set! *warn-on-reflection* true)

(defn on?
  [db name]
  (= "on" (:value (jdbc/execute-one! db ["SELECT value FROM community_setting WHERE name = ?" name] db/opts))))

(defn turn!
  "Turns choice `name` on, or off."
  [db name on]
  (db/with-write-tx [tx db]
    (jdbc/execute-one! tx
                       ["INSERT INTO community_setting (name, value) VALUES (?, ?)
                            ON CONFLICT (name) DO UPDATE SET value = excluded.value"
                        name
                        (if on "on" "off")])))
