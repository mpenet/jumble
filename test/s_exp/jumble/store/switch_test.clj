(ns s-exp.jumble.store.switch-test
  "The community's on/off choices."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [s-exp.jumble.fixtures :refer [*db* db-fixture]]
            [s-exp.jumble.store.switch :as switch]))

(set! *warn-on-reflection* true)

(use-fixtures :each db-fixture)

(deftest switch-test
  (testing "off until turned on, then off again"
    (is (not (switch/on? *db* "example")))
    (switch/turn! *db* "example" true)
    (is (switch/on? *db* "example"))
    (switch/turn! *db* "example" false)
    (is (not (switch/on? *db* "example")))))
