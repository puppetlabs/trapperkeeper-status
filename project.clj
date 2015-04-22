(def tk-version "1.1.1")
(def ks-version "1.1.0")

(defproject puppetlabs/trapperkeeper-status "0.1.0-SNAPSHOT"
  :description "A trapperkeeper service for getting the status of other trapperkeeper services."
  :url "https://github.com/puppetlabs/trapperkeeper-status"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :pedantic? :abort

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [cheshire "5.3.1"]
                 [compojure "1.1.8" :exclusions [commons-io org.clojure/tools.macro]]
                 [prismatic/schema "0.4.0"]
                 [ring/ring-json "0.3.1" :exclusions [commons-io]]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/trapperkeeper ~tk-version]]

  :lein-release {:scm         :git
                 :deploy-via  :lein-deploy}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  :profiles {:dev {:dependencies [[puppetlabs/http-client "0.4.4"]
                                  [puppetlabs/trapperkeeper ~tk-version :classifier "test" :scope "test"]
                                  [puppetlabs/trapperkeeper-webserver-jetty9 "1.3.1"]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test" :scope "test"]]}}

  :plugins [[lein-release "1.0.5"]]
  )
