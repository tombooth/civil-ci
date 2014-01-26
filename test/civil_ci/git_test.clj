(ns civil-ci.git-test
  (:require [clojure.test :refer :all]
            [civil-ci.common-test :refer :all]
            [civil-ci.git :refer :all]
            [clj-jgit.porcelain :as git]
            [clj-jgit.querying :as git-query]
            [clojure.java.io :as io]
            [fs.core :as fs]))


(use-fixtures :each test-dir-fixture)


(deftest test-get-or-create-config-repo

  (testing "creates directory if there isn't one"
    (let [path (join test-dir "foo")]
      (let [repo (get-or-create-config-repo path nil)] 
        (is (fs/exists? path))
        (is (not (nil? repo)))
        (is (fs/exists? (join path ".git"))))))

  (testing "if the directory has files (but not .git) in it then return nil"
    (let [path (join test-dir "with-files")]
      (fs/mkdir path)
      (fs/touch (join path "blah.txt"))
      (let [repo (get-or-create-config-repo path nil)]
        (is (nil? repo)))))

  (testing "if the path is a file then return nil"
    (let [path (join test-dir "file")]
      (fs/touch path)
      (let [repo (get-or-create-config-repo path nil)]
        (is (nil? repo)))))

  (testing "if there is already a git repo"
    (let [path (join test-dir "git-repo")]
      (fs/mkdir path)
      (git/git-init path)
      (let [repo (get-or-create-config-repo path nil)]
        (is (not (nil? repo)))))))


(deftest test-commit
  (testing "if i stage a change and commit there should be a new revision"
    (let [path (join test-dir "commit-to-repo")
          repo (make-config-repo path)]
      (spit (io/file path "server.json") "blah")
      (git/git-add (:git repo) "server.json")
      (commit repo "set server.json to blah")
      (let [git-repo (:git repo)
            commits (map #(git-query/commit-info git-repo %)
                         (git/git-log git-repo))]
        (is (= (count commits) 2))
        (is (= "set server.json to blah" (-> commits first :message))))))

  (testing "if repo is nil it doesn't do anything"
    (let [path (join test-dir "dont-commit-to-repo")
          repo (make-config-repo path)]
      (spit (io/file path "server.json") "blah")
      (git/git-add (:git repo) "server.json")
      (commit nil "set server.json to blah")
      (let [git-repo (:git repo)
            commits (map #(git-query/commit-info git-repo %)
                         (git/git-log git-repo))]
        (is (= (count commits) 1))))))



