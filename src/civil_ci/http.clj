(ns civil-ci.http
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [cheshire.core :as json]
            [civil-ci.data :as data]
            [digest]))



(defn validate [hash & rules]
  (if hash
    (if (empty? rules)
      hash
      (let [parts (map #(% hash) rules)]
        (if (some #(= :missing %) parts)
          nil
          (apply merge {} (filter #(not (nil? %)) parts)))))
    nil))

(defn- check-and-fail-with [fail-val]
  (fn [key & sub-rules]
    (fn [hash]
      (if hash
       (if-let [value (apply validate (hash key) sub-rules)]
         {key value}
         fail-val)
       fail-val))))

(def required (check-and-fail-with :missing))

(def optional (check-and-fail-with nil))

(defn default [hash]
  (fn [_]
    hash))



(defn- add-step [job step]
  (let [new-steps (conj (vec (:steps job)) step)]
    (assoc job :steps new-steps)))

(defn- commit-step [repo job step]
  (swap! job add-step step)
  (data/commit repo "Added a new build step")
  {:status 200 :body (json/generate-string step)})

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
                                  (default {:steps []})
                                  (required :name)
                                  (optional :steps))]
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
              (POST "/steps" [:as request]
                    (if-let [content-type ((:headers request) "content-type")]
                      (cond (= content-type "text/plain")
                            (let [script (slurp (:body request))
                                  step {:script script}]
                              (commit-step repo job step))
                            
                            (= content-type "application/json")
                            (let [json (json/parse-string (slurp (:body request)) true)]
                              (if-let [step (validate json
                                                      (required :script))]
                                (commit-step repo job step)
                                {:status 400 :body "Invalid step"}))
                            
                            :else
                            {:status 400 :body "Invalid content-type"})
                      {:status 400 :body "content-type header required"}))

              (GET "/steps" []
                   {:status 200
                    :body (json/generate-string (:steps @job))})))
   
   (route/not-found "Endpoint not found")))

