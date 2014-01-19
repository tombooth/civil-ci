(ns civil-ci.http-test
  (:require [clojure.test :refer :all]
            [civil-ci.http :refer :all]
            [cheshire.core :as json]))

(defn make-request
  ([resource web-app]
     (web-app {:request-method :get :uri resource}))
  ([resource web-app params]
     (web-app {:request-method :get :uri resource :params params}))
  ([method resource web-app params body]
     (make-request method resource web-app {} params body))
  ([method resource web-app headers params body]
     (web-app {:request-method method :uri resource
               :headers headers :params params
               :body (java.io.StringReader. body)})))



(deftest test-jobs
  (testing "return a job"
    (let [routes (bind-routes nil (atom {})
                              (atom {"some-id" (atom {:name "Some Job"})}))
          response (make-request "/jobs/some-id" routes {:id "some-id"})]
      (is (= (:status response) 200))
      (is (= (json/parse-string (:body response)) {"name" "Some Job"}))))

  (testing "404s when no job"
    (let [routes (bind-routes nil (atom {}) (atom {}))
          response (make-request "/jobs/foo" routes {:id "foo"})]
      (is (= (:status response) 404))))

  (testing "gets a list of jobs"
    (let [routes (bind-routes nil (atom {}) (atom {"1" (atom {:name "a"})
                                               "2" (atom {:name "b"})}))
          response (make-request "/jobs" routes {})]
      (is (= (:status response) 200))
      (is (= (json/parse-string (:body response)) [{"id" "1" "name" "a"}
                                                   {"id" "2" "name" "b"}]))))

  (testing "gets an empty array when no jobs"
    (let [routes (bind-routes nil (atom {}) (atom {}))
          response (make-request "/jobs" routes {})]
      (is (= (:status response) 200))
      (is (= (json/parse-string (:body response)) []))))

  (testing "you can add a new job"
    (let [server-config (atom {:jobs []})
          jobs-config (atom {})
          routes (bind-routes nil server-config jobs-config)
          response (make-request :post "/jobs" routes {}
                                 "{\"name\":\"New Job\"}")]
      (is (= (:status response) 200))
      (is (not (nil? (json/parse-string (:body response)))))
      (let [id (-> @jobs-config keys first)
            job @(@jobs-config id)]
        (is (not (nil? id)))
        (is (= (:name job) "New Job"))
        (is (= (:steps job) [])))))
  
  (testing "if the body is invalid, reject new job"
    (let [server-config (atom {:jobs []})
          jobs-config (atom {})
          routes (bind-routes nil server-config jobs-config)
          response (make-request :post "/jobs" routes {}
                                 "{\"foo\":\"bar\"}")]
      (is (= (:status response) 400))))

  (testing "receive text/plain and add steps"
    (let [server-config (atom {:jobs ["id"]})
          jobs-config (atom {"id" (atom {:name "Job" :steps []})})
          routes (bind-routes nil server-config jobs-config)
          response (make-request :post "/jobs/id/steps" routes
                                 {"content-type" "text/plain"}
                                 {:id "id"} "some script")]
      (is (= (:status response) 200))
      (is (= (-> @(@jobs-config "id") :steps first)
             {:script "some script"}))))

  (testing "receive application/json and add steps"
    (let [server-config (atom {:jobs ["id"]})
          jobs-config (atom {"id" (atom {:name "Job" :steps []})})
          routes (bind-routes nil server-config jobs-config)
          response (make-request :post "/jobs/id/steps" routes
                                 {"content-type" "application/json"}
                                 {:id "id"} "{\"script\":\"some script\"}")]
      (is (= (:status response) 200))
      (is (= (-> @(@jobs-config "id") :steps first)
             {:script "some script"}))))

  (testing "application/json needs to validate"
    (let [server-config (atom {:jobs ["id"]})
          jobs-config (atom {"id" (atom {:name "Job" :steps []})})
          routes (bind-routes nil server-config jobs-config)
          response (make-request :post "/jobs/id/steps" routes
                                 {"content-type" "application/json"}
                                 {:id "id"} "{\"foo\":\"bar\"}")]
      (is (= (:status response) 400))))

  (testing "only accept text/plain or application/json for steps atm"
    (let [server-config (atom {:jobs ["id"]})
          jobs-config (atom {"id" (atom {:name "Job" :steps []})})
          routes (bind-routes nil server-config jobs-config)
          response (make-request :post "/jobs/id/steps" routes
                                 {"content-type" "blah"}
                                 {:id "id"} "some script")]
      (is (= (:status response) 400))))

  (testing "get a list of steps"
    (let [server-config (atom {:jobs ["id"]})
          jobs-config (atom {"id" (atom {:name "Job" :steps [{:script "foo"}
                                                             {:script "bar"}]})})
          routes (bind-routes nil server-config jobs-config)
          response (make-request "/jobs/id/steps" routes {:id "id"})]
      (is (= (:status response) 200))
      (is (= (json/parse-string (:body response) true)
             [{:script "foo"} {:script "bar"}])))))


(deftest test-validate
  (testing "if it doesn't have all manditory then dump"
    (is (nil? (validate {:name "bar"} [:id]))))

  (testing "if it has more than manditory or optional dump"
    (is (= (validate {:id "foo" :name "bar" :other 0} [:id] [:name])
           {:id "foo" :name "bar"})))

  (testing "doesn't mind optional not being there"
    (is (= (validate {:id "foo"} [:id] [:name])
           {:id "foo"})))

  (testing "should allow defaults with optional"
    (is (= (validate {:id "foo"} [:id] [:name :steps] {:steps []})
           {:id "foo" :steps []})))

  (testing "passed in should override defaults"
    (is (= (validate {:id "foo" :steps ["blah"]} [:id] [:name :steps] {:steps []})
           {:id "foo" :steps ["blah"]}))))



