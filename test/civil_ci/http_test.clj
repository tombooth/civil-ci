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
     (web-app {:request-method method :uri resource :params params
               :body (java.io.StringReader. body)})))



(deftest test-jobs
  (testing "return a job"
    (let [routes (bind-routes (atom {}) (atom {"some-id" (atom {:name "Some Job"})}))
          response (make-request "/jobs/some-id" routes {:id "some-id"})]
      (is (= (:status response) 200))
      (is (= (json/parse-string (:body response)) {"name" "Some Job"}))))

  (testing "404s when no job"
    (let [routes (bind-routes (atom {}) (atom {}))
          response (make-request "/jobs/foo" routes {:id "foo"})]
      (is (= (:status response) 404))))

  (testing "gets a list of jobs"
    (let [routes (bind-routes (atom {}) (atom {"1" (atom {:name "a"})
                                               "2" (atom {:name "b"})}))
          response (make-request "/jobs" routes {})]
      (is (= (:status response) 200))
      (is (= (json/parse-string (:body response)) [{"id" "1" "name" "a"}
                                                   {"id" "2" "name" "b"}]))))

  (testing "gets an empty array when no jobs"
    (let [routes (bind-routes (atom {}) (atom {}))
          response (make-request "/jobs" routes {})]
      (is (= (:status response) 200))
      (is (= (json/parse-string (:body response)) [])))))


