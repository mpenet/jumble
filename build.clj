(ns build
  "Builds the application uberjar: clojure -T:build uber, with the version
  and commit from JUMBLE_VERSION and JUMBLE_REVISION when set (CI, the
  image's build arguments)."
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
    (b/write-file {:path (str class-dir "/jumble/version.edn")
                   :string (pr-str {:version (or (not-empty (System/getenv "JUMBLE_VERSION")) "dev")
                                    :revision (or (not-empty (System/getenv "JUMBLE_REVISION")) "unknown")})})
    (b/compile-clj {:basis basis
                    :ns-compile ['s-exp.jumble]
                    :class-dir class-dir
                    :compile-opts {:direct-linking true}})
    (b/uber {:basis basis
             :class-dir class-dir
             :uber-file uber-file
             :main 's-exp.jumble})))
