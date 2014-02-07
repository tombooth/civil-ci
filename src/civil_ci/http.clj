(ns civil-ci.http
  (:require [compojure.core :refer :all]
            [org.httpkit.server :refer [with-channel send! on-close close websocket?]]
            [clojure.set :refer [difference]]
            [civil-ci.validation :refer :all]
            [clojure.core.async :as async]
            [compojure.route :as route]
            [cheshire.core :as json]
            [civil-ci.data :as data]
            [civil-ci.git :as git]
            [clojure.data]
            [digest]))




(defn- add-step [job key step]
  (let [build (job key)
        new-steps (conj (vec (:steps build)) step)
        new-build (assoc build :steps new-steps)]
    (assoc job key new-build)))

(defn- update-step [job key step ordinal]
  (let [build (job key)
        head (take ordinal (:steps build))
        tail (drop (+ ordinal 1) (:steps build))
        new-build (assoc build :steps (concat head [step] tail))]
    (assoc job key new-build)))

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

(defn- extract-step [request]
  (if-let [content-type ((:headers request) "content-type")]
    (cond (= content-type "text/plain")
          (let [script (slurp (:body request))]
            {:script script})
          
          (= content-type "application/json")
          (let [json (json/parse-string (slurp (:body request)) true)]
            (if-let [step (validate json (required :script))] step :invalid-step))
          
          :else
          :invalid-content-type)
    :no-conent-type))

(defn- to-int [string] (try (Integer. string) (catch NumberFormatException e nil)))

(defn- valid-ordinal? [ordinal job key]
  (if-let [ordinal-int (to-int ordinal)]
    (let [steps (:steps (@job key))]
      (and (< ordinal-int (count steps))
           (>= ordinal-int 0)))))

(defn- jobs-from-config [jobs-config] (map (fn [[_ hash]] @hash) jobs-config))


(def step-error {:no-content-type "Need to provide a content type"
                 :invalid-content-type "Only expects application/json or text/plain"
                 :invalid-step "Step provided was invalid"})


(defn- diff [old new]
  (if (or (vector? new) (list? new) (seq? new))
    (let [new-set (set new)
          old-set (set old)]
      [(vec (difference old-set new-set))
       (vec (difference new-set old-set))
       nil])
    (clojure.data/diff old new)))

(defn diff-watcher
  ([atom out-fn] (diff-watcher atom identity out-fn))
  ([atom munge-fn out-fn]
     (let [key (str (System/currentTimeMillis) (rand-int 10000))]
       (add-watch atom key (fn [_ _ old new]
                             (let [munged-old (munge-fn old)
                                   munged-new (munge-fn new)]
                               (if (not (= munged-old munged-new))
                                 (let [[removed added _] (diff munged-old munged-new)]
                                   (out-fn {:added (if (empty? added) nil added)
                                            :removed (if (empty? removed) nil removed)}))))))
       key)))

(defn state-stream
  ([state-atom] (state-stream state-atom identity))
  ([state-atom munge-fn]
     (fn [request]
       (with-channel request channel
         (if (not (nil? state-atom))
           (let [initial-value (munge-fn @state-atom)]
             (if (websocket? channel)
               (let [key (diff-watcher state-atom munge-fn
                                       (fn [changed]
                                         (send! channel (json/generate-string changed))))]
                 (on-close channel (fn [_] (remove-watch state-atom key)))
                 (send! channel (json/generate-string {:initial initial-value})))
               (send! channel {:status 200
                               :body (json/generate-string initial-value)}
                      true)))
           (send! channel {:status 404
                           :body (json/generate-string {:error "Resource not found"})} true))))))


(defn build-routes [repo job-id job history key build-channel]
  (routes (POST "/steps" [:as request]
                (let [step (extract-step request)]
                  (if (keyword? step)
                    {:status 400 :body (step-error step)}
                    (do (swap! job add-step key step)
                        (git/commit repo "Added a new build step")
                        {:status 200 :body (json/generate-string step)}))))

          (GET "/steps" []
               (state-stream job #(:steps (% key))))

          (PUT "/steps/:ordinal" [ordinal :as request]
               (if (valid-ordinal? ordinal job key)
                 (let [step (extract-step request)
                       ordinal-int (to-int ordinal)]
                  (if (keyword? step)
                    {:status 400 :body (step-error step)}
                    (do (swap! job update-step key step ordinal-int)
                        (git/commit repo (str "Updated build step " ordinal))
                        {:status 200 :body (json/generate-string step)})))
                 {:status 400 :body "Invalid ordinal"}))

          (GET "/run" []
               (state-stream history #(% key)))

          (POST "/run" []
                (let [id (digest/sha1 (str (:name @job) (System/currentTimeMillis) (rand-int 1000)))
                      build-item {:id id :job-id job-id :type key :config (@job key)}
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
        (state-stream jobs-config jobs-from-config))
   
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
             (let [id (digest/sha-1 (str body (System/currentTimeMillis)))
                   job-with-id (assoc job :id id)]
               (swap! jobs-config assoc id (atom job-with-id))
               (add-history jobs-history id)
               (git/commit repo (str "A new job with id '" id "' has been added"))
               {:status 200 :body (json/generate-string job-with-id)})
             {:status 400 :body "Invalid job"})))
   
   (GET "/jobs/:id" [id]
        (state-stream (@jobs-config id)))

   (context "/jobs/:id" [id]
            (let-routes [job (@jobs-config id)
                         history (get-history jobs-history id)]
                        (POST "/run" [] {:status 307 :headers {"Location" (str "/jobs/" id "/build/run")}})
                        (context "/workspace" []
                                 (build-routes repo id job history :workspace build-channel))
                        (context "/build" []
                                 (build-routes repo id job history :build build-channel))))

   (GET "/queue" [] {:status 200 :body (json/generate-string @build-buffer)})
   
   (route/not-found "Endpoint not found")))






