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
               (if repo (git/add-to repo path)))))

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



(defn- get-value [file repo]
  (if (fs/file? file)
    (let [file-ref (atom (json/parse-string (slurp file) true))]
      (add-value-watcher file-ref file repo)
      file-ref)))

(defn- assoc-file [path filename repo]
  (fn [hash id]
    (if-let [config (get-value (io/file path id filename)
                                repo)]
      (assoc hash id config)
      (do (println (str filename " missing for id: " id))
          hash))))

(defn- add-hash-entry [key value-ref path filename repo]
  (let [file (io/file path key filename)
        directory (io/file path key)]
    (fs/mkdir directory)
    (spit file (json/generate-string @value-ref))
    (if repo (git/add-to repo (fs/absolute-path file)))
    (add-value-watcher value-ref file repo)))

(defn- remove-hash-entry [key path repo]
  (let [directory (io/file path key)]
    (fs/delete-dir directory)
    (if repo (git/remove-from repo (fs/absolute-path directory)))))

(defn- get-hash [path filename repo config-ref keys-keyword]
  (let [hash-ref (atom (reduce (assoc-file path filename repo)
                           {} (@config-ref keys-keyword)))]
    (add-hash-watcher hash-ref path
                      #(swap! config-ref assoc keys-keyword %)
                      #(add-hash-entry %1 %2 path filename repo )
                      #(remove-hash-entry % path repo))
    hash-ref))



(defn get-server-config [path repo]
  (get-value (io/file path "server.json") repo))

(defn get-job-config [path repo server-config]
  (get-hash path "job.json" repo server-config :jobs))

(defn get-job-history [path server-config]
  (get-hash path "history.json" nil server-config :jobs))

