(ns civil-ci.data
  (:require [fs.core :as fs]
            [clj-jgit.porcelain :as git]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.set :refer [difference]]))


(defn- init-repo [path template]
  (if (not (nil? template))
    (git/git-clone-full template path)
    (git/git-init path)))

(defn- get-repo [path]
  (if (git/discover-repo path)
    (git/load-repo path)
    nil))

(defn get-or-create-config-repo [path template]
  (if (fs/file? path)
    (println "Config path provided was a file")
    (if (not (fs/exists? path))
      (if (fs/mkdirs path)
        (init-repo path template))
      (get-repo path))))

(defn- add-config-watcher [reference file]
  (add-watch reference (fs/absolute-path file)
             (fn [path _ _ new-state]
               (spit path (json/generate-string new-state)))))

(defn- get-config [file]
  (if (fs/exists? file)
    (let [config-ref (atom (json/parse-string (slurp file) true))]
      (add-config-watcher config-ref file)
      config-ref)))

(defn get-server-config [path]
  (get-config (io/file path "server.json")))

(defn- assoc-job-config [path]
  (fn [job-hash id]
    (if-let [config (get-config (io/file path id "job.json"))]
      (assoc job-hash id config)
      (do (println (str "job.json missing for job id: " id))
          job-hash))))

(defn- add-jobs-watcher [reference path server-config]
  (add-watch reference path
             (fn [path _ old-jobs new-jobs]
               (let [old-jobs-set (set (keys old-jobs))
                     new-jobs-set (set (keys new-jobs))
                     added (difference new-jobs-set old-jobs-set)
                     removed (difference old-jobs-set new-jobs-set)]
                 (swap! server-config assoc :jobs (keys new-jobs))
                 (doall (map #(fs/delete-dir (io/file path %)) removed))
                 (doall (map #(let [config-file (io/file path % "job.json")
                                    config (new-jobs %)]
                                (fs/mkdir (io/file path %))
                                (spit config-file
                                      (json/generate-string @config))
                                (add-config-watcher config config-file))
                             added))))))

(defn get-job-config [path server-config]
  (let [job-config (atom (reduce (assoc-job-config path)
                                 {} (:jobs @server-config)))]
    (add-jobs-watcher job-config path server-config)
    job-config))

