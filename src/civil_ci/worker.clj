(ns civil-ci.worker
  (:require [clojure.core.async :as async])
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




