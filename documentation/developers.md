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
  {:is-running :true
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
## Details

See [Query API](./query-api.md) and [Wire Format](./wire-formats.md) for details
 on user-facing functionality that the Status Service provides.

You'll want to document the format of your service's status data (at the various
levels of detail `:critical`, `:info`, and `:debug`) in your own documentation.
