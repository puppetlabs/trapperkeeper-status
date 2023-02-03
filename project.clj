(defproject puppetlabs/trapperkeeper-status "1.1.2-SNAPSHOT"
  :description "A trapperkeeper service for getting the status of other trapperkeeper services."
  :url "https://github.com/puppetlabs/trapperkeeper-status"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :min-lein-version "2.7.1"

  :parent-project {:coords [puppetlabs/clj-parent "5.1.1"]
                   :inherit [:managed-dependencies]}

  :pedantic? :abort

  :exclusions [org.clojure/clojure]

  :dependencies [[org.clojure/clojure]

                 [cheshire]
                 [org.tcrawley/dynapath "1.0.0"]
                 [slingshot]
                 [prismatic/schema]
                 [trptcolin/versioneer]
                 [ring/ring-defaults]
                 [org.clojure/java.jmx]
                 [org.clojure/tools.logging]

                 [puppetlabs/kitchensink]
                 [puppetlabs/trapperkeeper]
                 [puppetlabs/trapperkeeper-scheduler]
                 [puppetlabs/ring-middleware]
                 [puppetlabs/comidi]
                 [puppetlabs/i18n]
                 [puppetlabs/trapperkeeper-authorization]]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  :profiles {:dev {:dependencies [[org.bouncycastle/bcpkix-jdk15on]
                                  [puppetlabs/http-client]
                                  [puppetlabs/trapperkeeper :classifier "test"]
                                  [puppetlabs/trapperkeeper-webserver-jetty9]
                                  [puppetlabs/kitchensink :classifier "test"]]}}

  :plugins [[lein-parent "0.3.8"]
            [puppetlabs/i18n "0.8.0" :hooks false]])
