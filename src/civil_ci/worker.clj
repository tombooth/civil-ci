(ns civil-ci.worker
  (:require [clojure.core.async :as async]
            [civil-ci.docker :as docker])
  (:import [java.util LinkedList]))



(deftype UnboundedBuffer [^LinkedList buf]
  clojure.core.async.impl.protocols/Buffer
  (full? [this] false)
  (remove! [this]
    (.removeFirst buf))
  (add! [this item]
    (.addLast buf item))
  clojure.lang.Counted
  (count [this]
    (.size buf))
  clojure.lang.IDeref
  (deref [this]
    (if-let [sequence (seq buf)]
      sequence
      [])))



(defn unbounded-buffer [] (UnboundedBuffer. (LinkedList.)))

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
  (let [control-channel (async/chan)
        thread-channel (async/thread-call (worker-thread channel control-channel
                                                         history docker-path))]
    [thread-channel control-channel]))

(defn create-n [num build-channel history docker-path]
  (doall (map #(create % history docker-path) (repeat num build-channel))))

(defn stop [worker-channels]
  (doall (map (fn [[thread-channel control-channel]]
                (async/>!! control-channel :exit)
                (async/<!! thread-channel))
              worker-channels)))

