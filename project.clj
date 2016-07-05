(def tk-version "1.4.0")
(def ks-version "1.3.0")


(defproject puppetlabs/trapperkeeper-status "0.4.0"
  :description "A trapperkeeper service for getting the status of other trapperkeeper services."
  :url "https://github.com/puppetlabs/trapperkeeper-status"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :pedantic? :abort

  :exclusions [org.clojure/clojure]

  :dependencies [[org.clojure/clojure "1.7.0"]

                 ;; Dependencies which resolve version conflicts via
                 ;; :pedantic? :abort in transitive dependencies
                 [clj-time "0.11.0"]
                 [ring "1.4.0"]
                 [commons-codec "1.10"]
                 [org.clojure/tools.macro "0.1.5"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/tools.reader "1.0.0-alpha1"]
                 [org.clojure/tools.trace "0.7.9"]
                 [org.slf4j/slf4j-api "1.7.13"]
                 ;; end list of version conflict resolution dependencies

                 [cheshire "5.6.1"]
                 [prismatic/schema "1.1.1"]
                 ;; ring-defaults brings in a bad, old version of the servlet-api, which
                 ;; now has a new artifact name (javax.servlet/javax.servlet-api).  If we
                 ;; don't exclude the old one here, they'll both be brought in, and consumers
                 ;; will be subject to the whims of which one shows up on the classpath first.
                 ;; thus, we need to use exclusions here, even though we'd normally resolve
                 ;; this type of thing by just specifying a fixed dependency version.
                 [ring/ring-defaults "0.2.0" :exclusions [javax.servlet/servlet-api]]

                 [slingshot "0.12.2"]
                 [trptcolin/versioneer "0.2.0"]
                 [org.clojure/java.jmx "0.3.1"]

                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/ring-middleware "1.0.0"]
                 [puppetlabs/comidi "0.3.1"]]

  :lein-release {:scm         :git
                 :deploy-via  :lein-deploy}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  :profiles {:dev {:dependencies [
                                  ;; Begin transitive dependency resolution
                                  [clj-time "0.11.0"]
                                  [commons-io "2.5"]
                                  ;; End transitive dependency resolution

                                  [puppetlabs/http-client "0.5.0"]
                                  [puppetlabs/trapperkeeper ~tk-version :classifier "test"]
                                  [puppetlabs/trapperkeeper-webserver-jetty9 "1.5.6"]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test"]]}}

  :plugins [[lein-release "1.0.5"]])
