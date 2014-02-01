(ns civil-ci.worker-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async]
            [civil-ci.worker :as worker]
            [clojure.java.io :as io]
            [fs.core :as fs]))

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


(deftest test-set-history
  (testing "it should actually do what we want it to"
    (let [history (atom {:workspace [{:id "foo"} {:id "bar"}]})]
      (swap! history worker/set-history "foo" :workspace {:blah true})
      (is (= (:workspace @history)
             [{:id "foo" :blah true} {:id "bar"}])))))

(deftest test-worker-threads
  (testing "spin up and spin down"
    (let [build-chan (async/chan)
          history (atom {:job {:workspace []}})
          docker-path (fs/absolute-path (io/resource "json-args"))
          worker-channels (worker/create-n 1 build-chan history
                                           docker-path)]
      (is (= (worker/stop worker-channels)
             [0]))))

  (testing "run one fake build"
    (let [build-chan (async/chan)
          history (atom {:job (atom {:workspace [{:id "foo"}]})})
          docker-path (fs/absolute-path (io/resource "json-args"))
          worker-channels (worker/create-n 1 build-chan history
                                           docker-path)
          build-item {:id "foo" :job-id :job :type :workspace
                      :config {:steps ["#!/bin/bash\necho foo"]}}]
      (async/>!! build-chan build-item)
      (while (not (= (-> @history :job deref :workspace first :status) "finished"))
        (Thread/sleep 100))
      (is (= (worker/stop worker-channels)
             [1])))))
