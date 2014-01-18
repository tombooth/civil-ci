(ns civil-ci.core
  (:gen-class)
  (:require [docopt.core :as dc]
            [docopt.match :as dm]
            [fs.core :as fs]
            [clj-jgit.porcelain :as git]))

(defn- init-repo [path]
  (let [repo (git/git-init path)]
    (println "Setting up config repo")
    repo))

(defn- get-repo [path]
  (if (git/discover-repo path)
    (git/load-repo path)
    nil))

(defn get-or-create-config-repo [path]
  (if (fs/file? path)
    (println "Config path provided was a file")
    (if (not (fs/exists? path))
      (if (fs/mkdirs path)
        (init-repo path)
        (println "Failed to create repo"))
      (get-repo path))))


(def usage-string "Civil CI

Usage:
  civil-ci [options] <config-path>

Options:
  -h --help      Show this screen.
  -v --version   Show version.")

(def version "Civil CI 0.1.0")

(defn -main [& args]
  (let [arg-map (dm/match-argv (dc/parse usage-string) args)]
    (cond
     (or (nil? arg-map)
         (arg-map "--help")) (println usage-string)
         
     (arg-map "--version")   (println version)
         
     :else (if-let [repo (get-or-create-config-repo (arg-map "<config-path>"))]
                      (println "Loaded repo.")))))


