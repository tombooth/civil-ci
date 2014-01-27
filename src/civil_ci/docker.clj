(ns civil-ci.docker
  (:require [clojure.string :refer [join]]
            [me.raynes.conch :as conch]
            [fs.core :as fs]
            [clojure.java.io :as io]
            [digest]))


(defn- create-script [root i content]
  (let [file (io/file root (str i ".script"))]
    (spit file content)
    (fs/chmod "+x" file)
    file))

(defn- create-dockerfile [file num-steps]
  (let [docker-dir "/var/build-scripts"
        run-commands (map #(str "RUN " docker-dir "/" % ".script") (range num-steps))
        commands (str "FROM ubuntu\n\n"
                      "ADD ./scripts " docker-dir "\n\n"
                      (join "\n" run-commands) "\n\n"
                      "RUN rm -rf " docker-dir "\n")]
    (spit file commands)))



(defn create-build-dir [build-item]
  (let [build-dir (fs/temp-dir (str "civil" (:type build-item) (:id build-item)))
        scripts-dir (io/file build-dir "scripts")
        dockerfile (io/file build-dir "Dockerfile")
        steps (-> build-item :config :steps)]
    (fs/mkdir scripts-dir)
    (doall (map-indexed #(create-script scripts-dir %1 %2) steps))
    (create-dockerfile dockerfile (count steps))
    build-dir))



(defn build
  ([dir tag] (build dir tag "/usr/bin/docker"))
  ([dir tag docker-path]
     (let [dir-path (fs/absolute-path dir)]
       (conch/let-programs [docker docker-path]
                           (if tag
                             (docker "build" "-t" tag dir-path)
                             (docker "build" dir-path))))))


