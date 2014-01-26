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



(defn create [channel history]
  (let [control-channel (async/chan)]
    (async/thread (loop [[v ch] (async/alts!! [channel control-channel])]
                    (if (and (= ch control-channel)
                             (= v :exit))
                      (println "Exiting worker thread")
                      
                      (let [id (:id v)
                            job-id (:job-id v)
                            type (:type v)
                            job-history (@history job-id)]

                        (println (str "Starting build [JOB:" job-id "][ID:" id "]"))
                        (swap! job-history set-history id type {:status "running"})
                        
                        (try (let [build-dir (docker/create-build-dir v)
                                   build-log (docker/build build-dir nil)]
                               (println (str "Finished build [JOB:" job-id "][ID:" id "]"))
                               (swap! job-history set-history id type {:status "finished"
                                                                       :log build-log}))
                             (catch Exception e
                               (println (str "Thread blew up a bit: " (.getMessage e)))
                               (.printStackTrace e)
                               (swap! job-history set-history id type {:status "error"
                                                                       :error (.getMessage e)})))
                        
                        (recur (async/alts!! [channel control-channel]))))))
    control-channel))

(defn create-n [num build-channel history]
  (doall (map #(create % history) (repeat num build-channel))))

(defn stop [control-channels]
  (doall (map #(async/>!! % :exit) control-channels)))

