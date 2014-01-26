(ns civil-ci.worker-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async]
            [civil-ci.worker :as worker]))

(deftest test-unbounded-buffer
  (testing "returns good values when empty"
    (let [buffer (worker/unbounded-buffer)]
      (is (= (count buffer) 0))
      (is (= @buffer [])))))


(deftest test-buffer-with-channel
  (testing "add a couple of items"
    (let [buffer (worker/unbounded-buffer)
          channel (worker/build-channel buffer)]
      (async/>!! channel "foo")
      (async/>!! channel "bar")
      (is (= (count buffer) 2))
      (is (= @buffer ["foo" "bar"]))
      (is (= (async/<!! channel) "foo"))
      (is (= (count buffer) 1))
      (is (= @buffer ["bar"])))))

