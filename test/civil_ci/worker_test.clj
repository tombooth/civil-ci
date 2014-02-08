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
      (is (= @buffer ["bar"]))))

  (testing "watchers work"
    (let [buffer (worker/unbounded-buffer)
          channel (worker/build-channel buffer)
          changes (atom [])]
      (add-watch buffer :watch (fn [& vals] (swap! changes conj vals)))
      (async/>!! channel "foo")
      (is (= (count @changes) 1))
      (is (= (first @changes) [:watch buffer [] ["foo"]]))
      (async/<!! channel)
      (is (= (count @changes) 2))
      (is (= (second @changes) [:watch buffer ["foo"] []]))
      (remove-watch buffer :watch)
      (async/>!! channel "bar")
      (is (= (count @changes) 2)))))


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
          workers (worker/create-n 1 build-chan history
                                   docker-path)]
      (is (:id (first workers)))
      (is (= (worker/stop-all workers)
             [0]))))

  (testing "run one fake build"
    (let [build-chan (async/chan)
          history (atom {:job (atom {:workspace [{:id "foo"}]})})
          docker-path (fs/absolute-path (io/resource "json-args"))
          workers (worker/create-n 1 build-chan history
                                   docker-path)
          build-item {:id "foo" :job-id :job :type :workspace
                      :config {:steps ["#!/bin/bash\necho foo"]}}]
      (async/>!! build-chan build-item)
      (while (not (= (-> @history :job deref :workspace first :status) "finished"))
        (Thread/sleep 100))
      (is (= (worker/stop-all workers)
             [1])))))
