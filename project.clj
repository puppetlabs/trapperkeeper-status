(defproject puppetlabs/trapperkeeper-status "1.2.0-SNAPSHOT"
  :description "A trapperkeeper service for getting the status of other trapperkeeper services."
  :url "https://github.com/puppetlabs/trapperkeeper-status"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :min-lein-version "2.9.0"

  :parent-project {:coords [puppetlabs/clj-parent "7.3.7"]
                   :inherit [:managed-dependencies]}

  :pedantic? :abort

  :dependencies [[org.clojure/clojure]
                 [cheshire]
                 [slingshot]
                 [prismatic/schema]
                 [trptcolin/versioneer]
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

  :profiles {:dev {:dependencies [[org.bouncycastle/bcpkix-jdk18on]
                                  [puppetlabs/http-client]
                                  [puppetlabs/trapperkeeper :classifier "test"]
                                  [com.puppetlabs/trapperkeeper-webserver-jetty10]
                                  [puppetlabs/kitchensink :classifier "test"]]}}

  :plugins [[lein-parent "0.3.9"]
            [puppetlabs/i18n "0.9.2" :hooks false]])
