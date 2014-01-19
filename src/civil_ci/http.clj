(ns civil-ci.http
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [cheshire.core :as json]
            [civil-ci.data :as data]
            [digest]))


(defn validate
  ([hash required] (validate hash required [] {}))
  ([hash required optional] (validate hash required
                                      optional {}))
  ([hash required optional defaults]
     (if (every? hash required)
       (reduce #(if (contains? hash %2)
                  (assoc %1 %2 (hash %2))
                  %1)
               (reduce #(assoc %1 %2 (hash %2))
                       defaults required)
               optional))))


(defn bind-routes [repo server-config jobs-config]
  (routes
   (GET "/jobs" []
        (let [jobs (map (fn [[id hash]] (assoc @hash :id id))
                        @jobs-config)]
          {:status 200 :body (json/generate-string jobs)}))
   
   (GET "/jobs/:id" [id]
        (if-let [job (@jobs-config id)]
          {:status 200 :body (json/generate-string @job)}
          {:status 404 :body "Job not found"}))

   (POST "/jobs" [id :as request]
         (let [body (slurp (:body request))
               json (json/parse-string body true)]
           (if-let [job (validate json [:name])]
             (let [id (digest/sha-1 (str body (System/currentTimeMillis)))]
               (swap! jobs-config assoc id (atom job))
               (data/commit repo (str "A new job with id '" id "' has been added"))
               {:status 200 :body (json/generate-string (assoc job :id id))})
             {:status 400 :body "Invalid job"})))
   
   (route/not-found "Endpoint not found")))

