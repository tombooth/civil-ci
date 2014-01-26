(ns civil-ci.docker-test
  (:require [clojure.test :refer :all]
            [civil-ci.docker :refer :all]
            [clojure.java.io :as io]
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



