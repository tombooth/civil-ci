(ns civil-ci.http-test
  (:require [clojure.test :refer :all]
            [civil-ci.http :refer :all]
            [civil-ci.worker :as worker]
            [org.httpkit.server :as httpkit]
            [clojure.core.async :as async]
            [cheshire.core :as json])
  (:import FakeChannel))


(extend-type FakeChannel
  httpkit/Channel
  (open? [ch] (.isOpen ch))
  (close [ch] (.close ch))
  (websocket? [ch] (.isWebsocket ch))
  (send!
    ([ch data] (httpkit/send! ch data false))
    ([ch data close-after-send]
       (.send ch data (boolean close-after-send))))
  (on-receive [ch callback] nil)
  (on-close [ch callback]
    (.onCloseHandler ch callback)))


(defn make-request
  ([resource web-app]
     (make-request :get resource web-app {} {} ""))
  ([resource web-app params]
     (make-request :get resource web-app {} params ""))
  ([method resource web-app params body]
     (make-request method resource web-app {} params body))
  ([method resource web-app headers params body]
     (web-app {:request-method method :uri resource
               :headers headers :params params
               :body (java.io.StringReader. body)})))

(defn make-async-request [resource web-app params websocket?]
  (let [channel (FakeChannel. (boolean websocket?))
        request {:request-method :get :uri resource :params params
                 :headers {"sec-websocket-key" "foo"} :websocket? websocket?
                 :async-channel channel}]
    (web-app request)
    channel))


(deftest test-stream
  (testing "jobs stream properly"
    (let [jobs-config (atom {"1" (atom {:id "1" :name "a"})})
          routes (bind-routes nil (atom {}) jobs-config nil nil nil nil)
          channel (make-async-request "/jobs" routes {} true)
          new-job (atom {:id "2" :name "b"})]
      (swap! jobs-config assoc "2" new-job)
      (httpkit/close channel)
      (let [sent (map #(json/parse-string % true) @channel)]
        (is (= (count sent) 2))
        (is (= (-> sent first :initial)
               [@(@jobs-config "1")]))
        (is (= (-> sent second :added count) 1))
        (is (= (-> sent second :added first)
               @new-job))
        (is (= (-> sent second :removed) nil)))))

  (testing "stream is closed if invalid"
    (let [routes (bind-routes nil (atom {}) (atom {}) nil nil nil nil)
          channel (make-async-request "/jobs/1" routes {} true)
          sent (map #(json/parse-string % true) @channel)]
      (is (= (count sent) 1))
      (is (not (httpkit/open? channel)))
      (is (string? (-> sent first :error)))))

  (testing "stream is closed if munge doesn't yeild a result"
    (let [routes (bind-routes nil (atom {})
                              (atom {"1" (atom {})}) (atom {"1" (atom {:workspace []})})
                              nil nil nil)
          channel (make-async-request "/jobs/1/workspace/run/foo" routes {} true)
          sent (map #(json/parse-string % true) @channel)]
      (is (= (count sent) 1))
      (is (not (httpkit/open? channel)))
      (is (string? (-> sent first :error)))))

  (testing "build queue works as expected"
    (let [build-buffer (worker/unbounded-buffer)
          build-channel (worker/build-channel build-buffer)
          routes (bind-routes nil nil nil nil build-channel build-buffer nil)
          channel (make-async-request "/queue" routes {} true)]
      (async/>!! build-channel "foo")
      (async/>!! build-channel "foo")
      (httpkit/close channel)
      (let [sent (map #(json/parse-string % true) @channel)]
        (is (= (count sent) 3))
        (is (= (-> sent first :initial) []))
        (is (= (-> sent second :added) ["foo"]))
        (is (= (-> sent second :removed) nil))
        (is (= (:added (nth sent 2)) [nil "foo"]))
        (is (= (:removed (nth sent 2)) nil))))))




(deftest test-jobs
  (testing "return a job"
    (let [routes (bind-routes nil (atom {})
                              (atom {"some-id" (atom {:name "Some Job"})}) nil nil nil nil)
          channel (make-async-request "/jobs/some-id" routes {:id "some-id"} false)
          response (first @channel)]
      (is (= (:status response) 200))
      (is (= (json/parse-string (:body response)) {"name" "Some Job"}))))

  (testing "404s when no job"
    (let [routes (bind-routes nil (atom {}) (atom {}) nil nil nil nil)
          channel (make-async-request "/jobs/foo" routes {:id "foo"} false)
          response (first @channel)]
      (is (= (:status response) 404))))

  (testing "gets a list of jobs"
    (let [routes (bind-routes nil (atom {}) (atom {"1" (atom {:id "1" :name "a"})
                                               "2" (atom {:id "2" :name "b"})}) nil nil nil nil)
          channel (make-async-request "/jobs" routes {} false)
          response (first @channel)]
      (is (= (:status response) 200))
      (is (= (json/parse-string (:body response)) [{"id" "1" "name" "a"}
                                                   {"id" "2" "name" "b"}]))))

  (testing "gets an empty array when no jobs"
    (let [routes (bind-routes nil (atom {}) (atom {}) nil nil nil nil)
          channel (make-async-request "/jobs" routes {} false)
          response (first @channel)]
      (is (= (:status response) 200))
      (is (= (json/parse-string (:body response)) []))))

  (testing "you can add a new job"
    (let [server-config (atom {:jobs []})
          jobs-config (atom {})
          jobs-history (atom {})
          routes (bind-routes nil server-config jobs-config jobs-history nil nil nil)
          response (make-request :post "/jobs" routes {}
                                 "{\"name\":\"New Job\"}")]
      (is (= (:status response) 200))
      (is (not (nil? (json/parse-string (:body response)))))
      (let [id (-> @jobs-config keys first)
            job @(@jobs-config id)
            history @(@jobs-history id)]
        (is (not (nil? id)))
        (is (= (:name job) "New Job"))
        (is (= (:workspace job) {:steps []}))
        (is (= (:build job) {:steps []}))
        (is (= (:workspace history) []))
        (is (= (:build history) [])))))
  
  (testing "if the body is invalid, reject new job"
    (let [server-config (atom {:jobs []})
          jobs-config (atom {})
          routes (bind-routes nil server-config jobs-config nil nil nil nil)
          response (make-request :post "/jobs" routes {}
                                 "{\"foo\":\"bar\"}")]
      (is (= (:status response) 400))))

  (testing "if a job exists but it has no history, it should be created"
    (let [server-config (atom {:jobs ["id"]})
          jobs-config (atom {"id" (atom {:workspace {:steps []}})})
          jobs-history (atom {})
          routes (bind-routes nil server-config jobs-config jobs-history nil nil nil)
          channel (make-async-request "/jobs/id/workspace/steps" routes {} false)
          response (first @channel)]
      (is (= (:status response) 200))
      (let [history @(@jobs-history "id")]
        (is (= (:workspace history) []))
        (is (= (:build history) [])))))

  (testing "/queue should return the current build queue"
    (let [buffer (worker/unbounded-buffer)
          build-channel (worker/build-channel buffer)
          routes (bind-routes nil nil nil nil build-channel buffer nil)
          channel (make-async-request "/queue" routes {} false)
          response (first @channel)]
      (is (= (:status response) 200))
      (is (= (json/parse-string (:body response) true)
             []))))

  (testing "/workers should return the ids of those connected"
    (let [workers (atom [{:id "foo" :thread-channel true :control-channel true}])
          routes (bind-routes nil nil nil nil nil nil workers)
          channel (make-async-request "/workers" routes {} false)
          response (first @channel)]
      (is (= (:status response) 200))
      (is (= (json/parse-string (:body response) true)
             [{:id "foo"}])))))


(deftest test-build-routes
  (testing "receive text/plain and add steps"
    (let [job (atom {:name "Job" :workspace {:steps []}})
          history (atom {:workspace []})
          routes (build-routes nil nil job history :workspace nil)
          response (make-request :post "/steps" routes
                                 {"content-type" "text/plain"}
                                 {:id "id"} "some script")]
      (is (= (:status response) 200))
      (is (= (-> @job :workspace :steps first)
             {:script "some script"}))))

  (testing "receive application/json and add steps"
    (let [job (atom {:name "Job" :workspace {:steps []}})
          history (atom {:workspace []})
          routes (build-routes nil nil job history :workspace nil)
          response (make-request :post "/steps" routes
                                 {"content-type" "application/json"}
                                 {:id "id"} "{\"script\":\"some script\"}")]
      (is (= (:status response) 200))
      (is (= (-> @job :workspace :steps first)
             {:script "some script"}))))

  (testing "application/json needs to validate"
    (let [job (atom {:name "Job" :workspace {:steps []}})
          history (atom {:workspace []})
          routes (build-routes nil nil job history :workspace nil)
          response (make-request :post "/steps" routes
                                 {"content-type" "application/json"}
                                 {:id "id"} "{\"foo\":\"bar\"}")]
      (is (= (:status response) 400))))

  (testing "only accept text/plain or application/json for steps atm"
    (let [job (atom {:name "Job" :workspace {:steps []}})
          history (atom {:workspace []})
          routes (build-routes nil nil job history :workspace nil)
          response (make-request :post "/steps" routes
                                 {"content-type" "blah"}
                                 {:id "id"} "some script")]
      (is (= (:status response) 400))))

  (testing "get a list of steps"
    (let [job (atom {:name "Job" :workspace {:steps [{:script "foo"}
                                                     {:script "bar"}]}})
          history (atom {:workspace []})
          routes (build-routes nil nil job history :workspace nil)
          channel (make-async-request "/steps" routes {:id "id"} false)
          response (first @channel)]
      (is (= (:status response) 200))
      (is (= (json/parse-string (:body response) true)
             [{:script "foo"} {:script "bar"}]))))

  (testing "if i add a second step then it is second in order"
    (let [job (atom {:name "Job" :workspace {:steps '({:script "step1"})}})
          history (atom {:workspace []})
          routes (build-routes nil nil job history :workspace nil)
          response (make-request :post "/steps" routes
                                 {"content-type" "application/json"}
                                 {:id "id"} "{\"script\":\"step2\"}")]
      (is (= (:status response) 200))
      (is (= (-> @job :workspace :steps)
             [{:script "step1"} {:script "step2"}]))))

  (testing "i can update a particular step by ordinal"
    (let [job (atom {:name "Job" :workspace {:steps [{:script "foo"}
                                                     {:script "bar"}]}})
          history (atom {:workspace []})
          routes (build-routes nil nil job history :workspace nil)
          response (make-request :put "/steps/1" routes {"content-type" "application/json"}
                                 {:id "id" :ordinal "1"} "{\"script\":\"bar-changed\"}")]
      (is (= (:status response) 200))
      (is (= (-> @job :workspace :steps)
             [{:script "foo"} {:script "bar-changed"}]))
      (is (= (json/parse-string (:body response) true)
             {:script "bar-changed"}))))

  (testing "trying to update a step with invalid ordinal results in 400"
    (let [job (atom {:name "Job" :workspace {:steps [{:script "foo"}]}})
          history (atom {:workspace []})
          routes (build-routes nil nil job history :workspace nil)
          response (make-request :put "/steps/1" routes {"content-type" "application/json"}
                                 {:id "id" :ordinal "1"} "{\"script\":\"bar-changed\"}")]
      (is (= (:status response) 400))
      (is (= (-> @job :workspace :steps)
             [{:script "foo"}]))))

  (testing "history gets returned"
    (let [job (atom {:name "Job" :workspace {:steps []}})
          history (atom {:workspace []})
          routes (build-routes nil nil job history :workspace nil)
          channel (make-async-request "/run" routes {} false)
          response (first @channel)]
      (is (= (:status response) 200))
      (is (= (json/parse-string (:body response) true)
             []))))

  (testing "when we run a build it causes history to be added and a build to be queued"
    (let [job (atom {:name "Job" :workspace {:steps []}})
          history (atom {:workspace []})
          buffer (worker/unbounded-buffer)
          queue (worker/build-channel buffer)
          routes (build-routes nil "job-id" job history :workspace queue)
          response (make-request :post "/run" routes {} "")]
      (is (= (:status response) 200))
      (is (= (count @buffer) 1))
      (is (= (count (-> @history :workspace)) 1))
      (let [history-item (-> @history :workspace first)
            build-item (-> @buffer first)]
        (is (= (:id history-item) (:id build-item)))
        (is (= (:job-id build-item) "job-id"))
        (is (= (:config build-item)
               (-> @job :workspace)))
        (is (= (:type build-item) :workspace))
        (is (= (:status history-item) "queued"))
        (is (= (json/parse-string (:body response) true)
               history-item)))))

  (testing "get a history item by id"
    (let [job (atom {:name "Job" :workspace {:steps []}})
          history (atom {:workspace [{:id "foo" :blah true} {:id "bar" :blah false}]})
          routes (build-routes nil nil job history :workspace nil)
          channel (make-async-request "/run/foo" routes {:id "foo"} false)
          response (first @channel)]
      (is (= (:status response) 200))
      (is (= (json/parse-string (:body response) true)
             {:id "foo" :blah true}))))

  (testing "get an invalid history item by id"
    (let [job (atom {:name "Job" :workspace {:steps []}})
          history (atom {:workspace []})
          routes (build-routes nil nil job history :workspace nil)
          channel (make-async-request "/run/foo" routes {:id "foo"} false)
          response (first @channel)]
      (is (= (:status response) 404)))))


(deftest test-diff-watcher
  (testing "simple test of diff-watcher"
    (let [watched-atom (atom ["foo"])
          diff-atom (atom [])
          key (diff-watcher watched-atom (partial swap! diff-atom conj))]
      (reset! watched-atom ["bar"])
      (is (= @diff-atom [{:added ["bar"] :removed ["foo"]}]))
      (remove-watch watched-atom key)
      (reset! watched-atom ["foo"])
      (is (= (count @diff-atom) 1))))

  (testing "with some munging"
    (let [watched-atom (atom {"foo" {:a "b"}})
          diff-atom (atom [])
          key (diff-watcher watched-atom
                            (fn [m] (map (fn [[_ val]] val) m))
                            (partial swap! diff-atom conj))]
      (reset! watched-atom {})
      (is (= @diff-atom [{:added nil :removed [{:a "b"}]}]))
      (remove-watch watched-atom key)))

  (testing "munging and adding"
    (let [watched-atom (atom {"foo" {:a "b"}})
          diff-atom (atom [])
          key (diff-watcher watched-atom
                            (fn [m] (map (fn [[_ val]] val) m))
                            (partial swap! diff-atom conj))]
      (swap! watched-atom assoc "bar" {:b "c"})
      (is (= @diff-atom [{:added [{:b "c"}] :removed nil}]))
      (remove-watch watched-atom key)))

  (testing "when munged doesn't change"
    (let [watched-atom (atom {:foo "bar" :a "b"})
          diff-atom (atom [])
          key (diff-watcher watched-atom :foo
                            (partial swap! diff-atom conj))]
      (swap! watched-atom assoc :a "c")
      (is (empty? @diff-atom))
      (remove-watch watched-atom key)))

  (testing "when multiple of the same are added to the list it still works"
    (let [watched-atom (atom {:foo ["foo"]})
          diff-atom (atom [])
          key (diff-watcher watched-atom :foo true
                            (partial swap! diff-atom conj))]
      (swap! watched-atom assoc :foo ["foo" "foo"])
      (is (= @diff-atom [{:added [nil "foo"] :removed nil}])))))


