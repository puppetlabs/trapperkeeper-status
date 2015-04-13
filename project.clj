(def tk-version "1.1.0")

(defproject puppetlabs/trapperkeeper-status "0.1.0-SNAPSHOT"
  :description "A trapperkeeper service for getting the status of other trapperkeeper services."
  :url "https://github.com/puppetlabs/trapperkeeper-status"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :pedantic? :abort

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [puppetlabs/trapperkeeper ~tk-version]]

  :lein-release {:scm         :git
                 :deploy-via  :lein-deploy}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  :profiles {:dev {:dependencies [[puppetlabs/trapperkeeper ~tk-version :classifier "test" :scope "test"]
                                  [puppetlabs/kitchensink "1.0.0" :classifier "test" :scope "test"]]}}

  :plugins [[lein-release "1.0.5"]]
  )
