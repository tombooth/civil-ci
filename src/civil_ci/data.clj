(ns civil-ci.data
  (:require [fs.core :as fs]
            [clj-jgit.porcelain :as git]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.set :refer [difference]]))


(defn- init-repo [path template]
  (if (not (nil? template))
    (:repo (git/git-clone-full template path))
    (git/git-init path)))

(defn- get-repo [path]
  (if (git/discover-repo path)
    (git/load-repo path)
    nil))

(defn get-or-create-config-repo [path template]
  (if (fs/file? path)
    (println "Config path provided was a file")
    (if-let [repo (if (not (fs/exists? path))
                    (if (fs/mkdirs path)
                      (init-repo path template))
                    (get-repo path))]
      {:git repo
       :root path})))

(defn- relative-path [base path]
  (let [root-path-pattern (re-pattern (str "^" base "/(.*)$"))]
    (if-let [match (re-matches root-path-pattern path)]
      (second match))))

(defn- git-change-operation [git-fn]
  (fn [repo path]
    (println "Git:" git-fn repo path)
    (if-let [git-path (relative-path (:root repo) path)]
      (git-fn (:git repo) git-path))))

(def add-to (git-change-operation git/git-add))
(def remove-from (git-change-operation git/git-rm))

(defn commit [repo message]
  (if repo
    (do (println "Committing:" message)
        (git/git-commit (:git repo) message))))

(defn- add-config-watcher [reference file repo]
  (add-watch reference (fs/absolute-path file)
             (fn [path _ _ new-state]
               (spit path (json/generate-string new-state))
               (add-to repo path))))

(defn- get-config [file repo]
  (if (fs/exists? file)
    (let [config-ref (atom (json/parse-string (slurp file) true))]
      (add-config-watcher config-ref file repo)
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

(defn- add-jobs-watcher [reference path repo server-config]
  (add-watch reference path
             (fn [path _ old-jobs new-jobs]
               (let [old-jobs-set (set (keys old-jobs))
                     new-jobs-set (set (keys new-jobs))
                     added (difference new-jobs-set old-jobs-set)
                     removed (difference old-jobs-set new-jobs-set)]
                 (swap! server-config assoc :jobs (keys new-jobs))
                 (doall (map #(let [job-dir (io/file path %)]
                                (fs/delete-dir job-dir)
                                (remove-from repo (str (fs/absolute-path job-dir))))
                             removed))
                 (doall (map #(let [config-file (io/file path % "job.json")
                                    config (new-jobs %)]
                                (fs/mkdir (io/file path %))
                                (spit config-file
                                      (json/generate-string @config))
                                (add-to repo (fs/absolute-path config-file))
                                (add-config-watcher config config-file repo))
                             added))))))

(defn get-job-config [path repo server-config]
  (let [job-config (atom (reduce (assoc-job-config path repo)
                                 {} (:jobs @server-config)))]
    (add-jobs-watcher job-config path repo server-config)
    job-config))

