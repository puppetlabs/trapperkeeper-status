(def tk-version "1.1.1")
(def ks-version "1.1.0")

(defproject puppetlabs/trapperkeeper-status "0.1.2-SNAPSHOT"
  :description "A trapperkeeper service for getting the status of other trapperkeeper services."
  :url "https://github.com/puppetlabs/trapperkeeper-status"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :pedantic? :abort

  :exclusions [org.clojure/clojure]

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/java.jdbc "0.3.7"]
                 [cheshire "5.3.1"]
                 [prismatic/schema "0.4.0"]
                 [ring/ring-json "0.3.1" :exclusions [ring/ring-core]]
                 [ring/ring-defaults "0.1.5"]
                 [slingshot "0.12.2"]
                 [puppetlabs/kitchensink ~ks-version :exclusions [clj-time]]
                 [puppetlabs/trapperkeeper ~tk-version :exclusions [clj-time org.clojure/tools.macro]]
                 [puppetlabs/comidi "0.1.3"]
                 [grimradical/clj-semver "0.3.0"]
                 [trptcolin/versioneer "0.2.0"]]

  :lein-release {:scm         :git
                 :deploy-via  :lein-deploy}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  :profiles {:dev {:dependencies [[puppetlabs/http-client "0.4.4"]
                                  [puppetlabs/trapperkeeper ~tk-version :classifier "test" :exclusions [clj-time org.clojure/tools.macro]]
                                  [puppetlabs/trapperkeeper-webserver-jetty9 "1.3.1" :exclusions [clj-time]]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test" :exclusions [clj-time]]]}}

  :plugins [[lein-release "1.0.5"]])
