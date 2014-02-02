(ns civil-ci.core
  (:gen-class)
  (:require [docopt.core :as dc]
            [docopt.match :as dm]
            [org.httpkit.server :as httpkit]
            [civil-ci.http :as http]
            [civil-ci.data :as data]
            [civil-ci.git :as git]
            [civil-ci.worker :as worker]))

(def usage-string "Civil CI

Usage:
  civil-ci [options] <config-path>
  civil-ci -h | --help
  civil-ci -v | --version

Options:
  -h --help                     Show this screen.
  -v --version                  Show version.
  --port=<port>                 Port for web server. [default:8080]
  --config-template=<url|path>  Git repo to clone when create a config directory
                                [default:https://github.com/tombooth/civil-ci-template.git]
  --workers=<num>               Number of worker threads. [default:2]
  --docker-path=<path>          Path to the docker executable. [default:/usr/bin/docker]
")

(def version "Civil CI 0.1.0")

(defn -main [& args]
  (let [arg-map (dm/match-argv (dc/parse usage-string) args)]
    (cond
     (or (nil? arg-map)
         (arg-map "--help")) (println usage-string)
         
     (arg-map "--version")   (println version)
         
     :else (let [path (arg-map "<config-path>")
                 port (Integer/parseInt (arg-map "--port"))
                 num-workers (Integer/parseInt (arg-map "--workers"))
                 docker-path (arg-map "--docker-path")]
             (if-let [repo (git/get-or-create-config-repo path (arg-map "--config-template"))]
               (if-let [server-config (data/get-server-config path repo)]
                 (let [job-config (data/get-job-config path repo server-config)
                       job-history (data/get-job-history path server-config)
                       build-buffer (worker/unbounded-buffer)
                       build-channel (worker/build-channel build-buffer)]
                         (httpkit/run-server (http/bind-routes repo server-config
                                                               job-config job-history
                                                               build-channel build-buffer)
                                             {:port port})
                         (worker/create-n num-workers build-channel job-history docker-path)
                         (println "Started"))
                 (println "Failed to load server.json"))
               (println "Failed to load configuration repository"))))))


