(ns civil-ci.git
  (:require [fs.core :as fs]
            [clj-jgit.porcelain :as git]))


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

