(ns civil-ci.validation-test
  (:require [clojure.test :refer :all]
            [civil-ci.validation :refer :all]))


(deftest test-validate
  (testing "if it doesn't have all manditory then dump"
    (is (nil? (validate {:name "bar"}
                        (required :id)))))

  (testing "if it has more than manditory or optional dump"
    (is (= (validate {:id "foo" :name "bar" :other 0}
                     (required :id)
                     (optional :name))
           {:id "foo" :name "bar"})))

  (testing "doesn't mind optional not being there"
    (is (= (validate {:id "foo"}
                     (required :id)
                     (optional :name))
           {:id "foo"})))

  (testing "should allow defaults with optional"
    (is (= (validate {:id "foo"}
                     (default {:steps []})
                     (required :id)
                     (optional :steps))
           {:id "foo" :steps []})))

  (testing "passed in should override defaults"
    (is (= (validate {:id "foo" :steps ["blah"]}
                     (default {:steps []})
                     (required :id)
                     (optional :steps))
           {:id "foo" :steps ["blah"]})))

  (testing "we can pull out sub-keys"
    (is (= (validate {:steps {:foo "1" :bar "2"}}
                     (optional :steps
                               (optional :foo)))
           {:steps {:foo "1"}})))

  (testing "sub-keys can require fields"
    (is (= (validate {:steps {:bar "1"}}
                     (optional :steps
                               (required :foo)))
           {})))

  (testing "required keys can have optional sub-keys"
    (is (= (validate {:foo {:bar "1" :other "2"}}
                     (required :foo
                               (optional :bar)))
           {:foo {:bar "1"}})))
  
  (testing "multiple levels of optional"
    (is (= (validate {} (optional :foo
                                  (optional :bar)))
           {})))

  (testing "an optional with a required"
    (is (= (validate {:foo {}} (optional :foo
                                         (required :bar)))
           {}))))


