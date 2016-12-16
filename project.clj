(defproject puppetlabs/trapperkeeper-status "0.7.0-SNAPSHOT"
  :description "A trapperkeeper service for getting the status of other trapperkeeper services."
  :url "https://github.com/puppetlabs/trapperkeeper-status"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :min-lein-version "2.7.1"

  :parent-project {:coords [puppetlabs/clj-parent "0.2.5"]
                   :inherit [:managed-dependencies]}

  :pedantic? :abort

  :exclusions [org.clojure/clojure]

  :dependencies [[org.clojure/clojure]

                 [cheshire]
                 [slingshot]
                 [prismatic/schema]
                 [trptcolin/versioneer]
                 [ring/ring-defaults]
                 [org.clojure/java.jmx]

                 [puppetlabs/kitchensink]
                 [puppetlabs/trapperkeeper]
                 [puppetlabs/trapperkeeper-scheduler]
                 [puppetlabs/ring-middleware]
                 [puppetlabs/comidi]
                 [puppetlabs/i18n]]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  :profiles {:dev {:dependencies [[puppetlabs/http-client]
                                  [puppetlabs/trapperkeeper :classifier "test"]
                                  [puppetlabs/trapperkeeper-webserver-jetty9]
                                  [puppetlabs/kitchensink :classifier "test"]]}}

  :plugins [[lein-parent "0.3.1"]
            [puppetlabs/i18n "0.4.3"]])
