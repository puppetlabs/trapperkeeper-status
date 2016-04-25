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
  (:require [puppetlabs.trapperkeeper.services.status.status-core :as status-core]))

(schema/defn ^:always-validate
  v1-status-callback :- status-core/StatusCallbackResponse
  [level :- status-core/ServiceStatusDetailLevel]
  {:state :running
   :status (get-basic-status-for-my-service-at-level level)})

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

For information on HTTP serving of the status endpoint, see the [Status Proxy documentation](./status-proxy-service.md).

## Details

See [Query API](./query-api.md) and [Wire Format](./wire-formats.md) for details
 on user-facing functionality that the Status Service provides.

You'll want to document the format of your service's status data (at the various
levels of detail `:critical`, `:info`, and `:debug`) in your own documentation.
