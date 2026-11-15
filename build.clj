(ns build
  "Builds the application uberjar: clojure -T:build uber"
  (:require [clojure.tools.build.api :as b]))

(def ^:private class-dir "target/classes")
(def ^:private uber-file "target/jumble.jar")

(defn clean
  [_]
  (b/delete {:path "target"}))

(defn uber
  [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs ["resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :ns-compile ['s-exp.jumble]
                    :class-dir class-dir
                    :compile-opts {:direct-linking true}})
    (b/uber {:basis basis
             :class-dir class-dir
             :uber-file uber-file
             :main 's-exp.jumble})))
