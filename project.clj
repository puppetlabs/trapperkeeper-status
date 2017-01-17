(defproject puppetlabs/trapperkeeper-status "0.7.1"
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
                 ;; ring-defaults brings in a bad, old version of the servlet-api, which
                 ;; now has a new artifact name (javax.servlet/javax.servlet-api).  If we
                 ;; don't exclude the old one here, they'll both be brought in, and consumers
                 ;; will be subject to the whims of which one shows up on the classpath first.
                 ;; thus, we need to use exclusions here, even though we'd normally resolve
                 ;; this type of thing by just specifying a fixed dependency version.
                 [ring/ring-defaults :exclusions [javax.servlet/servlet-api]]
                 [org.clojure/java.jmx]
                 [org.clojure/tools.logging]

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
