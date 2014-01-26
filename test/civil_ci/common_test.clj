(ns civil-ci.common-test
  (:require [clojure.test :refer :all]
            [fs.core :as fs]
            [clojure.java.io :as io]
            [clj-jgit.porcelain :as git]
            [cheshire.core :as json]))


(defn join [& args] (fs/absolute-path (apply io/file args)))
(def test-dir (join (fs/absolute-path fs/*cwd*) "test-fixtures"))

(defn read-json-file [path]
  (if (fs/file? path) (json/parse-string (slurp path) true)))

(defn make-config-repo
  ([destination] (make-config-repo destination "fixtures/spec-config"))
  ([destination source]
    (fs/copy-dir (io/resource source) destination)
    (let [repo (git/git-init destination)]
      (git/git-add repo ".")
      (git/git-commit repo "Initial commit")
      {:git repo
       :root destination})))

(defn status-category-in? [repo category relative-path]
  (let [status (git/git-status (:git repo))
        changed-set (status category)]
    (changed-set relative-path)))

(defn changed-in? [repo relative-path]
  (status-category-in? repo :changed relative-path))

(defn added-in? [repo relative-path]
  (status-category-in? repo :added relative-path))

(defn removed-from? [repo relative-path]
  (status-category-in? repo :removed relative-path))

(defn test-dir-fixture [fn]
  (fs/mkdir test-dir)
  (fn)
  (fs/delete-dir test-dir))


