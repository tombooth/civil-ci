(ns civil-ci.core-test
  (:require [clojure.test :refer :all]
            [civil-ci.core :refer :all]
            [clojure.java.io :as io]
            [fs.core :as fs]
            [clj-jgit.porcelain :as git]))

(defn join [& args] (fs/absolute-path (apply io/file args)))
(def test-dir (join (fs/absolute-path fs/*cwd*) "test-fixtures"))

(defn test-dir-fixture [fn]
  (fs/mkdir test-dir)
  (fn)
  (fs/delete-dir test-dir))

(use-fixtures :each test-dir-fixture)



(deftest test-get-or-create-config-repo

  (testing "creates directory if there isn't one"
    (let [path (join test-dir "foo")]
      (let [repo (get-or-create-config-repo path)] 
        (is (fs/exists? path))
        (is (not (nil? repo)))
        (is (fs/exists? (join path ".git"))))))

  (testing "if the directory has files (but not .git) in it then return nil"
    (let [path (join test-dir "with-files")]
      (fs/mkdir path)
      (fs/touch (join path "blah.txt"))
      (let [repo (get-or-create-config-repo path)]
        (is (nil? repo)))))

  (testing "if the path is a file then return nil"
    (let [path (join test-dir "file")]
      (fs/touch path)
      (let [repo (get-or-create-config-repo path)]
        (is (nil? repo)))))

  (testing "if there is already a git repo"
    (let [path (join test-dir "git-repo")]
      (fs/mkdir path)
      (git/git-init path)
      (let [repo (get-or-create-config-repo path)]
        (is (not (nil? repo)))))))


(deftest test-get-server-config

  (testing "given a file it populates"
    (let [path (join test-dir "server-config")]
      (fs/mkdir path)
      (fs/copy (io/resource "fixtures/server.json") (join path "server.json"))
      (let [server-config (get-server-config path)]
        (is (= (:jobs server-config) [])))))

  (testing "nil when file is non-existant"
    (let [path (join test-dir "liuhgq4")]
      (is (nil? (get-server-config path))))))


