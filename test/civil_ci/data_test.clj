(ns civil-ci.data-test
  (:require [clojure.test :refer :all]
            [civil-ci.data :refer :all]
            [clojure.java.io :as io]
            [fs.core :as fs]
            [clj-jgit.porcelain :as git]
            [clj-jgit.querying :as git-query]
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


(deftest test-get-server-config

  (testing "given a file it populates"
    (let [path (join test-dir "server-config")
          repo (make-config-repo path)]
      (let [server-config @(get-server-config path repo)]
        (is (= (:jobs server-config) ["some-id"])))))

  (testing "nil when file is non-existant"
    (let [path (join test-dir "liuhgq4")]
      (is (nil? (get-server-config path nil)))))

  (testing "file config changes then file should reflect it"
    (let [path (join test-dir "server-config-change")
          config-path (join path "server.json")
          repo (make-config-repo path)]
      (let [mutable-server-config (get-server-config path repo)]
        (swap! mutable-server-config assoc :key "value")
        (is (= (read-json-file config-path)
               {:jobs ["some-id"] :key "value"}))
        (is (changed-in? repo "server.json"))))))

(deftest test-get-job-config

  (testing "given the spec config, load it"
    (let [path (join test-dir "job-config")
          repo (make-config-repo path)]
      (let [server-config (get-server-config path repo)
            job-config @(get-job-config path repo server-config)]
        (is (= (-> job-config keys count) 1))
        (is (= (-> (job-config "some-id") deref :name) "Some Job")))))

  (testing "if there as missing job configs those jobs should just be dropped"
    (let [path (join test-dir "invalid-job-config")
          repo (make-config-repo path "fixtures/invalid-job-config")]
      (let [server-config (get-server-config path repo)
            job-config @(get-job-config path repo server-config)]
        (is (= (-> job-config keys count) 1)))))

  (testing "if a job's config is changed it is reflected in the json file"
    (let [path (join test-dir "changing-job-config")
          repo (make-config-repo path)]
      (let [server-config (get-server-config path repo)
            job-config (get-job-config path repo server-config)
            some-id-config (@job-config "some-id")]
        (swap! some-id-config assoc :key "value")
        (is (= (read-json-file (join path "some-id/job.json"))
               {:name "Some Job" :key "value"}))
        (is (changed-in? repo "some-id/job.json")))))

  (testing "if a jobs added files should change"
    (let [path (join test-dir "add-job-config")
          repo (make-config-repo path)]
      (let [server-config (get-server-config path repo)
            job-config (get-job-config path repo server-config)
            new-job-config (atom {:name "New Job"})]
        (swap! job-config assoc "new-job" new-job-config)
        (is (some #(= % "new-job") (:jobs @server-config)))
        (is (fs/file? (join path "new-job/job.json")))
        (is (= (read-json-file (join path "new-job/job.json"))
               {:name "New Job"}))
        (is (added-in? repo "new-job/job.json"))
        (is (changed-in? repo "server.json")))))

  (testing "if job is removed files should change"
    (let [path (join test-dir "remove-job-config")
          repo (make-config-repo path)]
      (let [server-config (get-server-config path repo)
            job-config (get-job-config path repo server-config)]
        (swap! job-config dissoc "some-id")
        (is (not (some #(= % "some-id") (:jobs @server-config))))
        (is (not (fs/directory? (join path "some-id"))))
        (is (removed-from? repo "some-id/job.json")))))

  (testing "when a job is added, changes should be reflected in the fs"
    (let [path (join test-dir "add-job-and-change-config")
          repo (make-config-repo path)]
      (let [server-config (get-server-config path repo)
            job-config (get-job-config path repo server-config)
            new-job-config (atom {:name "New Job"})]
        (swap! job-config assoc "new-job" new-job-config)
        (swap! new-job-config assoc :key "value")
        (is (= (read-json-file (join path "new-job/job.json"))
               {:name "New Job" :key "value"}))
        ;; when data gains a commit function I should use it
        ;; here to separate this into a change rather than an
        ;; incremented add
        (is (added-in? repo "new-job/job.json"))))))



