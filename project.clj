(defproject civil-ci "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [clj-jgit "0.6.4"]
                 [docopt "0.6.1"]
                 [me.raynes/fs "1.4.4"]
                 [me.raynes/conch "0.5.0"]
                 [cheshire "5.2.0"]
                 [http-kit "2.1.16"]
                 [compojure "1.1.6"]
                 [digest "1.4.3"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]]
  :java-source-paths ["test/java"]
  :main civil-ci.core)
