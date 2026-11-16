(ns s-exp.jumble.version
  "The version running, and the commit it was built from: written into the
  jar by the build (build.clj), dev without one."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(def info
  "`{:version :revision}`: a release's version (2026.11.0), main for the
  latest commit, or dev; the commit, unknown when not built from one."
  (delay (merge {:version "dev" :revision "unknown"}
                (some-> (io/resource "jumble/version.edn") slurp edn/read-string))))
