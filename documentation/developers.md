## TL;DR Quick Start

1. Add `puppetlabs/trapperkeeper-status` to your lein deps.
2. Add `puppetlabs.trapperkeeper.services.status.status-service/status-service`
   to your `bootstrap.cfg`.
3. Add `[:StatusService register-status]` to your TK service's deps.
4. Call `status-core/get-artifact-version` to dynamically get the
   version info for your service.
5. Call `register-status` to register a callback function that returns
   status info for your service.
6. Define your status callback function.

Code sample:
```clj
(ns foo
  (:require [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [schema.core :as schema]))

(schema/defn ^:always-validate
  v1-status-callback :- status-core/StatusCallbackResponse
  [level :- status-core/ServiceStatusDetailLevel]
  {:state :running
   :status (get-basic-status-for-my-service-at-level level)
   :alerts (get-alerts-at-level level)})

(defservice foo-service
  [[:StatusService register-status]]
  (init [this context]
    (register-status "foo-service"
      (status-core/get-artifact-version "puppetlabs" "foo")
      1
      v1-status-callback)
    context))
```

## Implementing Your Status Function

Your status callback function should return a map that matches the
status-core/StatusCallbackResponse schema. This means it should return a :state
key, a :status key, and :alerts. :status and :alerts can change depending on
the level specified to the function. Generally, :alerts should only be provided
at the info level, unless you've decided you have some critical condition that
is best notified about in prose. Keep in mind that most of our tools that will
surface :alerts should query at info level.

The `puppetlabs.trapperkeeper.services.status.status-core` namespace contains
some utilities to aid in the implementation of your status functions.  In
particular, the `level->int` function defines an ordering for status levels as
`:critical < :info < :debug`, and the `compare-levels` function can be used to
compare status levels.  This is especially useful in conjunction with the
`cond->` macro from `clojure.core`.  Here's an example of how a status function
might be implemented to utilize the `compare-levels` function:
```clj
(require '[puppetlabs.trapperkeeper.services.status.status-core :as status-core])

(defn my-status
  [level]
  (let [level>= (partial status-core/compare-levels >= level)]
    {:state running
     :status (cond-> {:this-is-critical "foo"}
               (level>= :info) (assoc :bar "bar"
                                      :baz "baz")
               (level>= :debug) (assoc :x "x"
                                       :y "y"
                                       :z "y"))}))
```

### Implementing Alerts

Alerts are returned by the  status function and allow you to expose human
readable information about the state of your application. This makes it easy
for status to be displayed and checked by unopinionated consumers. This works
best if all conditions which may affect the availability of your service
result in an alert.

Examples:
```clj
{:severity :error
 :message "Cannot connect to database"
 :type :my-service/database-unavailable
 :details {}}

{:severity :warning
 :message "Repo may not be initialized"
 :type :my-service/uninitialized-repo
 :details { :repo "some-repo"} }
```

- `:severity` : The severity of the alert represents how consumers should support it.
    - `:error` Should be used if and only if the alert is serious enough to
      prevent the service from being in the `:running` state.
    - `:warning` Should be used for conditions which will likely result in
      degraded service without affecting the `:state`
    - `:info` : Should be used for other alerts that do not impact the
      functioning of the service. These should not be present when the status
      level is greater than info.
- `:message` : A human readable string that can be displayed to users.
- `:type` : This can be used by consumer logic to find particular alerts that
  are important to them. `:type` is optional but should be included.
- `:details` : A map of with more computer readable information  about the
  cause of the alert. The schema of details must be the same for all alerts
  of the same type. If multiple alerts of a type are possible they should be
  distinguishable by details. `:details` is optional key but should be
  included.

## Exposing the /status endpoint

If you want your registered status functions to be accessible via HTTP(S),
you need to route the status service accordingly:

```
webserver: {
  default: {
    ssl-port: 9001
    ssl-cert: /etc/ssl/certs/myhostname.pem
    ssl-key: /etc/ssl/private_keys/myhostname.pem
    ssl-ca-cert: /etc/ssl/certs/ca.pem
    default-server: true
  }
}

web-router-service: {
  "puppetlabs.trapperkeeper.services.status.status-service/status-service": {
    route: /status
    server: default
  }
}
```

For information on proxying plaintext `/status` requests to an otherwise HTTPS
protected server, see the [Status Proxy documentation](./status-proxy-service.md).

## Details

See [Query API](./query-api.md) and [Wire Format](./wire-formats.md) for details
 on user-facing functionality that the Status Service provides.

You'll want to document the format of your service's status data (at the various
levels of detail `:critical`, `:info`, and `:debug`) in your own documentation.
