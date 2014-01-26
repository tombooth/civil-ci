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




(defn- add-value-watcher [reference file repo]
  (add-watch reference (fs/absolute-path file)
             (fn [path _ _ new-state]
               (spit path (json/generate-string new-state))
               (add-to repo path))))

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
    (add-to repo (fs/absolute-path config-file))
    (add-value-watcher config config-file repo)))

(defn- remove-job [path id repo]
  (let [job-dir (io/file path id)]
    (fs/delete-dir job-dir)
    (remove-from repo (str (fs/absolute-path job-dir)))))


(defn get-job-config [path repo server-config]
  (let [job-config (atom (reduce (assoc-job-config path repo)
                                 {} (:jobs @server-config)))]
    (add-hash-watcher job-config path
                      #(swap! server-config assoc :jobs %)
                      #(add-job path %1 %2 repo )
                      #(remove-job path % repo))
    job-config))


