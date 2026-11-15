(ns s-exp.jumble.view.avatar-test
  "Members' avatars: their initials on a colour of the community's palette,
  the same for them everywhere."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.jumble.view.avatar :as avatar]))

(set! *warn-on-reflection* true)

(deftest initials-test
  (testing "the first letters of the first and last names"
    (is (= "AM" (avatar/initials "Alice Martin")))
    (is (= "AS" (avatar/initials "Anna Maria de Souza")))
    (is (= "JP" (avatar/initials "Jean-Luc Picard")))
    (is (= "A" (avatar/initials "alice"))))
  (testing "any alphabet, whole characters"
    (is (= "ÉD" (avatar/initials "élodie durand")))
    (is (= "ØL" (avatar/initials "Øystein Lie")))
    (is (= "𝐀" (avatar/initials "𝐀lpha"))))
  (testing "letters, not what surrounds them"
    (is (= "BO" (avatar/initials "  (Bob) O'Neil ")))
    (is (= "C" (avatar/initials "carol@example.com"))))
  (testing "nothing to go by"
    (is (= "?" (avatar/initials "")))
    (is (= "?" (avatar/initials nil)))
    (is (= "?" (avatar/initials "🙂 ")))))

(deftest avatar-test
  (testing "decorative: the name is always beside it"
    (is (= "true" (:aria-hidden (second (avatar/avatar {:id 3 :name "Alice Martin"}))))))
  (testing "a colour of their own, kept from page to page"
    (is (= (avatar/avatar {:id 3 :name "Alice"}) (avatar/avatar {:id 3 :name "Alice"})))
    (is (not= (:class (second (avatar/avatar {:id 3 :name "A"}))) (:class (second (avatar/avatar {:id 4 :name "A"}))))))
  (testing "the initials shown"
    (is (= "AM" (last (avatar/avatar {:id 3 :name "Alice Martin"}))))))
