(ns civil-ci.data-test
  (:require [clojure.test :refer :all]
            [civil-ci.common-test :refer :all]
            [civil-ci.data :refer :all]
            [clojure.java.io :as io]
            [fs.core :as fs]
            [clj-jgit.porcelain :as git]
            [cheshire.core :as json]))


(use-fixtures :each test-dir-fixture)


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
        (is (added-in? repo "new-job/job.json")))))

  (testing "if directory already exists it doesn't blow up"
    (let [path (join test-dir "job-dir-already-exists")
          repo (make-config-repo path "fixtures/job-dir-exists")
          server-config (get-server-config path repo)
          job-config (get-job-config path repo server-config)
          new-job-config (atom {:name "New Job"})]
      (swap! job-config assoc "some-id" new-job-config)
      (fs/exists? (join path "some-id" "job.json"))))

  (testing "if i don't care about git and pass no repo don't blow up"
    (let [path (join test-dir "job-dir-already-exists")
          server-config (get-server-config path nil)
          job-config (get-job-config path nil server-config)
          new-job-config (atom {:name "New Job"})]
      (swap! job-config assoc "new-id" new-job-config)
      (fs/exists? (join path "new-id" "job.json")))))




