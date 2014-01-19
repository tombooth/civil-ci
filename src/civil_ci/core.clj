(ns civil-ci.core
  (:gen-class)
  (:require [docopt.core :as dc]
            [docopt.match :as dm]
            [org.httpkit.server :as httpkit]
            [civil-ci.http :as http]
            [civil-ci.data :as data]))

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
         
     :else (if-let [repo (data/get-or-create-config-repo path (arg-map "--config-template"))]
             (if-let [server-config (data/get-server-config path repo)]
               (let [job-config (data/get-job-config path repo server-config)]
                 (httpkit/run-server (http/bind-routes server-config job-config)
                                     {:port port})
                 (println "Started"))
               (println "Failed to load server.json"))
             (println "Failed to load configuration repository")))))


