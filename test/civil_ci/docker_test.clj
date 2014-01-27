(ns civil-ci.docker-test
  (:require [clojure.test :refer :all]
            [civil-ci.docker :refer :all]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [fs.core :as fs]))


(deftest test-create-build-dir
  (testing "directory layout and content should be correct"
    (let [build-item {:id "blah" :type :build :config {:steps ["foo" "bar"]}}
          build-dir (create-build-dir build-item)]
      (is (fs/file? (io/file build-dir "Dockerfile")))
      (is (fs/directory? (io/file build-dir "scripts")))
      (let [step-0 (io/file build-dir "scripts" "0.script")
            step-1 (io/file build-dir "scripts" "1.script")]
        (is (= (slurp step-0) "foo"))
        (is (= (slurp step-1) "bar"))))))

(def json-args-path (fs/absolute-path
                     (io/resource "json-args")))

(def test-dir (io/resource "fixtures"))
(def test-dir-path (fs/absolute-path test-dir))

(deftest test-build
  (testing "without tag"
    (let [output (build test-dir nil json-args-path)
          parsed-output (json/parse-string output true)]
      (is (= parsed-output ["build" test-dir-path]))))

  (testing "with tag"
    (let [output (build test-dir "bar" json-args-path)
          parsed-output (json/parse-string output true)]
      (is (= parsed-output ["build" "-t" "bar" test-dir-path])))))


