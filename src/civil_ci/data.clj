(ns civil-ci.data
  (:require [fs.core :as fs]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [civil-ci.git :as git]
            [clojure.set :refer [difference]]))



(defn- add-value-watcher [reference file repo]
  (add-watch reference (fs/absolute-path file)
             (fn [path _ _ new-state]
               (spit path (json/generate-string new-state))
               (git/add-to repo path))))

(defn- add-hash-watcher [reference path changed-fn add-fn remove-fn]
  (add-watch reference path
             (fn [path _ old-hash new-hash]
               (let [old-key-set (set (keys old-hash))
                     new-key-set (set (keys new-hash))
                     added (difference new-key-set old-key-set)
                     removed (difference old-key-set new-key-set)
                     new-keys (keys new-hash)]
                 (changed-fn new-keys)
                 (doall (map remove-fn removed))
                 (doall (map #(add-fn % (new-hash %)) added))))))



(defn- get-config [file repo]
  (if (fs/exists? file)
    (let [config-ref (atom (json/parse-string (slurp file) true))]
      (add-value-watcher config-ref file repo)
      config-ref)))

(defn get-server-config [path repo]
  (get-config (io/file path "server.json")
              repo))

(defn- assoc-job-config [path repo]
  (fn [job-hash id]
    (if-let [config (get-config (io/file path id "job.json")
                                repo)]
      (assoc job-hash id config)
      (do (println (str "job.json missing for job id: " id))
          job-hash))))

(defn- add-job [path id config repo]
  (let [config-file (io/file path id "job.json")]
    (fs/mkdir (io/file path id))
    (spit config-file
          (json/generate-string @config))
    (git/add-to repo (fs/absolute-path config-file))
    (add-value-watcher config config-file repo)))

(defn- remove-job [path id repo]
  (let [job-dir (io/file path id)]
    (fs/delete-dir job-dir)
    (git/remove-from repo (str (fs/absolute-path job-dir)))))


(defn get-job-config [path repo server-config]
  (let [job-config (atom (reduce (assoc-job-config path repo)
                                 {} (:jobs @server-config)))]
    (add-hash-watcher job-config path
                      #(swap! server-config assoc :jobs %)
                      #(add-job path %1 %2 repo )
                      #(remove-job path % repo))
    job-config))


