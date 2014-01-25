(ns civil-ci.http
  (:require [compojure.core :refer :all]
            [civil-ci.validation :refer :all]
            [compojure.route :as route]
            [cheshire.core :as json]
            [civil-ci.data :as data]
            [digest]))




(defn- add-step [job key step]
  (let [build (job key)
        new-steps (conj (vec (:steps build)) step)
        new-build (assoc build :steps new-steps)]
    (assoc job key new-build)))

(defn- commit-step [repo job key step]
  (swap! job add-step key step)
  (data/commit repo "Added a new build step")
  {:status 200 :body (json/generate-string step)})



(defn build-routes [repo job key]
  (routes (POST "/steps" [:as request]
                (if-let [content-type ((:headers request) "content-type")]
                  (cond (= content-type "text/plain")
                        (let [script (slurp (:body request))
                              step {:script script}]
                          (commit-step repo job key step))
                        
                        (= content-type "application/json")
                        (let [json (json/parse-string (slurp (:body request)) true)]
                          (if-let [step (validate json
                                                  (required :script))]
                            (commit-step repo job key step)
                            {:status 400 :body "Invalid step"}))
                        
                        :else
                        {:status 400 :body "Invalid content-type"})
                  {:status 400 :body "content-type header required"}))

          (GET "/steps" []
               {:status 200
                :body (json/generate-string (:steps (@job key)))})))



(defn bind-routes [repo server-config jobs-config]
  (routes
   (GET "/jobs" []
        (let [jobs (map (fn [[id hash]] (assoc @hash :id id))
                        @jobs-config)]
          {:status 200 :body (json/generate-string jobs)}))
   
   (POST "/jobs" [id :as request]
         (let [body (slurp (:body request))
               json (json/parse-string body true)]
           (if-let [job (validate json
                                  (default {:workspace {:steps []}
                                            :build {:steps []}})
                                  (required :name)
                                  (optional :workspace
                                            (optional :steps))
                                  (optional :build
                                            (optional :steps)))]
             (let [id (digest/sha-1 (str body (System/currentTimeMillis)))]
               (swap! jobs-config assoc id (atom job))
               (data/commit repo (str "A new job with id '" id "' has been added"))
               {:status 200 :body (json/generate-string (assoc job :id id))})
             {:status 400 :body "Invalid job"})))
   
   (GET "/jobs/:id" [id]
        (if-let [job (@jobs-config id)]
          {:status 200 :body (json/generate-string @job)}
          {:status 404 :body "Job not found"}))

   (context "/jobs/:id" [id]
            (let-routes [job (@jobs-config id)]
                        (context "/workspace" []
                                 (build-routes repo job :workspace))
                        (context "/build" []
                                 (build-routes repo job :build))))
   
   (route/not-found "Endpoint not found")))






