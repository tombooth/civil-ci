(ns civil-ci.core
  (:gen-class)
  (:require [docopt.core :as dc]
            [docopt.match :as dm]
            [fs.core :as fs]
            [clj-jgit.porcelain :as git]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

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

(defn- get-config [file]
  (if (fs/exists? file)
      (json/parse-string (slurp file) true)))

(defn get-server-config [path]
  (get-config (io/file path "server.json")))

(defn get-job-config [path server-config]
  (reduce (fn [job-hash id]
            (if-let [config (get-config (io/file path id "job.json"))]
              (assoc job-hash id config)
              (do (println (str "job.json missing for job id: " id))
                  job-hash)))
          {}
          (:jobs server-config)))

(def usage-string "Civil CI

Usage:
  civil-ci [options] <config-path>

Options:
  -h --help                     Show this screen.
  -v --version                  Show version.
  --config-template=<url|path>  Git repo to clone when create a config directory
                                [default:https://github.com/tombooth/civil-ci-template.git]")

(def version "Civil CI 0.1.0")

(defn -main [& args]
  (let [arg-map (dm/match-argv (dc/parse usage-string) args)
        path (arg-map "<config-path>")]
    (cond
     (or (nil? arg-map)
         (arg-map "--help")) (println usage-string)
         
     (arg-map "--version")   (println version)
         
     :else (if-let [repo (get-or-create-config-repo path (arg-map "--config-template"))]
             (if-let [server-config (get-server-config path)]
               (let [job-config (get-job-config path server-config)]
                 (println "Started"))
               (println "Failed to load server.json"))
             (println "Failed to load configuration repository")))))


