(ns civil-ci.http
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [cheshire.core :as json]))


(defn bind-routes [server-config jobs-config]
  (routes
   (GET "/jobs" []
        (let [jobs (map (fn [[id hash]] (assoc hash :id id))
                        @jobs-config)]
          {:status 200 :body (json/generate-string jobs)}))
   (GET "/jobs/:id" [id]
        (if-let [job (@jobs-config id)]
          {:status 200 :body (json/generate-string job)}
          {:status 404 :body "Job not found"}))
   (route/not-found "Endpoint not found")))

