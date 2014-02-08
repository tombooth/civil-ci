(ns civil-ci.worker
  (:require [clojure.core.async :as async]
            [civil-ci.docker :as docker])
  (:import [java.util.concurrent ConcurrentLinkedQueue]))


(defn- to-seq [buf]
  (if-let [sequence (seq (.toArray buf))]
      sequence
      []))

(defn- fire-watches [watches ref old new]
  (doseq [[key watch-fn] watches]
    (watch-fn key ref old new)))

(deftype UnboundedBuffer [buf watches]
  
  clojure.core.async.impl.protocols/Buffer
  (full? [this] false)
  (remove! [this]
    (let [old (to-seq buf)
          out (.poll buf)]
      (fire-watches @watches this old (to-seq buf))
      out))
  (add! [this item]
    (let [old (to-seq buf)]
      (.add buf item)
      (fire-watches @watches this old (to-seq buf))))
  
  clojure.lang.Counted
  (count [this]
    (.size buf))
  
  clojure.lang.IRef
  (deref [this] (to-seq buf))
  (setValidator [this fn] nil)
  (getValidator [this] nil)
  (getWatches [this] @watches)
  (addWatch [this key fn]
    (swap! watches assoc key fn)
    this)
  (removeWatch [this key]
    (swap! watches dissoc key)
    nil))


(defn unbounded-buffer [] (UnboundedBuffer. (ConcurrentLinkedQueue.)
                                            (atom {})))

(defn build-channel
  ([] (build-channel (unbounded-buffer)))
  ([buffer]
     (async/chan buffer)))



(defn set-history [history id type hash]
  (let [new-history (map #(if (= (:id %) id)
                            (merge % hash)
                            %)
                         (history type))]
    (assoc history type new-history)))



(defn- run-build [build-item history docker-path]
  (let [id (:id build-item)
        job-id (:job-id build-item)
        type (:type build-item)
        job-history (@history job-id)]
    
    (try
      (println (str "Starting build [JOB:" job-id "][ID:" id "]"))
      (swap! job-history set-history id type {:status "running"})
    
      (let [build-dir (docker/create-build-dir build-item)
            build-log (docker/build build-dir nil docker-path)]
        (println (str "Finished build [JOB:" job-id "][ID:" id "]"))
        (swap! job-history set-history id type {:status "finished"
                                                   :log build-log}))
      (catch Exception e
        (println (str "Thread blew up a bit: " (.getMessage e)))
        (.printStackTrace e)
        (swap! job-history set-history id type {:status "error"
                                                :error (.getMessage e)})))))

(defn- worker-thread [channel control-channel history docker-path]
  (fn []
    (let [build-count (atom 0)]
      (loop [[v ch] (async/alts!! [channel control-channel])]
        (if (and (= ch control-channel)
                 (= v :exit))
          @build-count
          
          (do (run-build v history docker-path)
              (swap! build-count inc)
              (recur (async/alts!! [channel control-channel]))))))))

(defn create [channel history docker-path]
  (let [id (digest/sha-1 (str (System/currentTimeMillis) (rand-int 1000)))
        control-channel (async/chan)
        thread-channel (async/thread-call (worker-thread channel control-channel
                                                         history docker-path))]
    {:id id
     :thread-channel thread-channel
     :control-channel control-channel}))

(defn create-n [num build-channel history docker-path]
  (doall (map #(create % history docker-path) (repeat num build-channel))))

(defn stop [worker]
  (async/>!! (:control-channel worker) :exit)
  (async/<!! (:thread-channel worker)))

(defn stop-all [workers]
  (doall (map #(stop %) workers)))

