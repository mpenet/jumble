(ns s-exp.jumble.page.health-test
  "GET /healthz."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.store.db :as db]
            [s-exp.jumble.fixtures :refer [call-with-db-path]]
            [s-exp.jumble.page.health :as health]))

(deftest check-test
  (testing "database reachable"
    (call-with-db-path (fn [path]
                         (is (= 200 (:status (health/check {:ctx {:db (db/datasource path)}})))))))
  (testing "database unreachable"
    (with-open [ds (db/datasource "/nonexistent-dir/jumble.db")]
      (is (= 503 (:status (health/check {:ctx {:db ds}})))))))
