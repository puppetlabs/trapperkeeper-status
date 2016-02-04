(def tk-version "1.2.0")
(def ks-version "1.2.0")

(defproject puppetlabs/trapperkeeper-status "0.3.1-SNAPSHOT"
  :description "A trapperkeeper service for getting the status of other trapperkeeper services."
  :url "https://github.com/puppetlabs/trapperkeeper-status"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :pedantic? :abort

  :exclusions [org.clojure/clojure]

  :dependencies [[org.clojure/clojure "1.7.0"]

                 ;; Dependencies which resolve version conflicts via
                 ;; :pedantic? :abort in transitive dependencies
                 [clj-time "0.10.0"]
                 [ring/ring-core "1.4.0"]
                 [commons-codec "1.9"]
                 [org.clojure/tools.macro "0.1.5"]
                 ;; end list of version conflict resolution dependencies

                 [cheshire "5.3.1"]
                 [prismatic/schema "1.0.4"]
                 ;; ring-defaults brings in a bad, old version of the servlet-api, which
                 ;; now has a new artifact name (javax.servlet/javax.servlet-api).  If we
                 ;; don't exclude the old one here, they'll both be brought in, and consumers
                 ;; will be subject to the whims of which one shows up on the classpath first.
                 ;; thus, we need to use exclusions here, even though we'd normally resolve
                 ;; this type of thing by just specifying a fixed dependency version.
                 [ring/ring-defaults "0.1.5" :exclusions [javax.servlet/servlet-api]]

                 [slingshot "0.12.2"]
                 [trptcolin/versioneer "0.2.0"]
                 [org.clojure/java.jmx "0.3.1"]

                 [grimradical/clj-semver "0.3.0"]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/comidi "0.3.1"]]

  :lein-release {:scm         :git
                 :deploy-via  :lein-deploy}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  :profiles {:dev {:dependencies [[puppetlabs/http-client "0.4.4" :exclusions [commons-io]]
                                  [puppetlabs/trapperkeeper ~tk-version :classifier "test" :exclusions [clj-time org.clojure/tools.macro]]
                                  [puppetlabs/trapperkeeper-webserver-jetty9 "1.3.1" :exclusions [clj-time]]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test" :exclusions [clj-time]]]}}

  :plugins [[lein-release "1.0.5"]])
