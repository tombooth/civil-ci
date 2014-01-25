(ns civil-ci.validation)



(defn validate [hash & rules]
  (if hash
    (if (empty? rules)
      hash
      (let [parts (map #(% hash) rules)]
        (if (some #(= :missing %) parts)
          nil
          (apply merge {} (filter #(not (nil? %)) parts)))))
    nil))

(defn- check-and-fail-with [fail-val]
  (fn [key & sub-rules]
    (fn [hash]
      (if hash
       (if-let [value (apply validate (hash key) sub-rules)]
         {key value}
         fail-val)
       fail-val))))

(def required (check-and-fail-with :missing))

(def optional (check-and-fail-with nil))

(defn default [hash]
  (fn [_]
    hash))


