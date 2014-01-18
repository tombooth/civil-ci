(ns civil-ci.core
  (:gen-class)
  (:require [docopt.core :as dc]
            [docopt.match :as dm]
            [fs.core :as fs]
            [clj-jgit.porcelain :as git]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [org.httpkit.server :as httpkit]
            [civil-ci.http :as http]
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

(def usage-string "Civil CI

Usage:
  civil-ci [options] <config-path>

Options:
  -h --help                     Show this screen.
  -v --version                  Show version.
  --port=<port>                 Port for web server. [default:8080]
  --config-template=<url|path>  Git repo to clone when create a config directory
                                [default:https://github.com/tombooth/civil-ci-template.git]")

(def version "Civil CI 0.1.0")

(defn -main [& args]
  (let [arg-map (dm/match-argv (dc/parse usage-string) args)
        path (arg-map "<config-path>")
        port (Integer/parseInt (arg-map "--port"))]
    (cond
     (or (nil? arg-map)
         (arg-map "--help")) (println usage-string)
         
     (arg-map "--version")   (println version)
         
     :else (if-let [repo (get-or-create-config-repo path (arg-map "--config-template"))]
             (if-let [server-config (get-server-config path)]
               (let [job-config (get-job-config path server-config)]
                 (httpkit/run-server (http/bind-routes server-config job-config)
                                     {:port port})
                 (println "Started"))
               (println "Failed to load server.json"))
             (println "Failed to load configuration repository")))))


