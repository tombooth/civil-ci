(ns civil-ci.http
  (:require [compojure.core :refer :all]
            [civil-ci.validation :refer :all]
            [clojure.core.async :as async]
            [compojure.route :as route]
            [cheshire.core :as json]
            [civil-ci.data :as data]
            [civil-ci.git :as git]
            [digest]))




(defn- add-step [job key step]
  (let [build (job key)
        new-steps (conj (vec (:steps build)) step)
        new-build (assoc build :steps new-steps)]
    (assoc job key new-build)))

(defn- commit-step [repo job key step]
  (swap! job add-step key step)
  (git/commit repo "Added a new build step")
  {:status 200 :body (json/generate-string step)})

(defn- add-history [jobs-history id]
  (let [history-atom (atom {:workspace [] :build []})]
    (swap! jobs-history assoc id history-atom)
    history-atom))

(defn- get-history [jobs-history id]
  (if-let [history-atom (@jobs-history id)]
    history-atom
    (add-history jobs-history id)))

(defn- add-history-item [history key item]
  (let [build-history (history key)]
    (assoc history key (conj build-history item))))


(defn build-routes [repo job history key build-channel]
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
                :body (json/generate-string (:steps (@job key)))})

          (GET "/run" []
               {:status 200
                :body (json/generate-string (@history key))})

          (POST "/run" []
                (let [id (digest/sha1 (str (:name @job) (System/currentTimeMillis) (rand-int 1000)))
                      build-item {:id id :type key :config (@job key)}
                      history-item {:id id :status "queued"}]
                  (async/>!! build-channel build-item)
                  (swap! history add-history-item key history-item)
                  {:status 200 :body (json/generate-string history-item)}))

          (GET "/run/:id" [id]
               (if-let [history-item (first (filter #(= id (:id %)) (@history key)))]
                 {:status 200 :body (json/generate-string history-item)}
                 {:status 404 :body "Invalid run id"}))))



(defn bind-routes [repo server-config jobs-config jobs-history build-channel build-buffer]
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
               (add-history jobs-history id)
               (git/commit repo (str "A new job with id '" id "' has been added"))
               {:status 200 :body (json/generate-string (assoc job :id id))})
             {:status 400 :body "Invalid job"})))
   
   (GET "/jobs/:id" [id]
        (if-let [job (@jobs-config id)]
          {:status 200 :body (json/generate-string @job)}
          {:status 404 :body "Job not found"}))

   (context "/jobs/:id" [id]
            (let-routes [job (@jobs-config id)
                         history (get-history jobs-history id)]
                        (POST "/run" [] {:status 307 :headers {"Location" (str "/jobs/" id "/build/run")}})
                        (context "/workspace" []
                                 (build-routes repo job history :workspace build-channel))
                        (context "/build" []
                                 (build-routes repo job history :build build-channel))))

   (GET "/queue" [] {:status 200 :body (json/generate-string @build-buffer)})
   
   (route/not-found "Endpoint not found")))






