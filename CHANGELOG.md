## 0.7.1

This is a bugfix release.

* Fix for a schema error that can cause gc/cpu usage updates to fail when
  computed process gc and/or cpu times are not whole numbers.

* Fix for an erroneous schema error which could be written to the log
  in the event that a trapperkeeper app is shutdown before the
  trapperkeeper-status service is started.

## 0.7.0

This is a feature release.

* [PE-13539](https://tickets.puppetlabs.com/browse/PE-13539) At startup, log
  version numbers for all services that register themselves with the status
  service.
* [TK-414](https://tickets.puppetlabs.com/browse/TK-414) Add metrics about
  CPU usage and GC CPU usage to the default JVM metrics available from the
  HTTP endpoint at `debug` level.
* [TK-401](https://tickets.puppetlabs.com/browse/TK-401) Include service name
  in log message when a service's callback fails due to error or timeout

## 0.6.0

This is a feature release.

* Add ability for TK-status to periodically log status data to a file in
  JSON format

* Add GC counts and file descriptor usage to the `jvm-metrics` section of the
  status output

## 0.5.0

This is a feature release.

* Add the optional `timeout` query parameter to the HTTP endpoints. The value
  must be an integer that specifies the timeout in seconds. If a timeout is not
  provided, then the default is used.

* Add the optional `timeout` argument to the `get-status` protocol method. The
  value must be an integer that specifies the timeout in seconds. If a timeout
  is not provided, then the default is used.

* Increase the default critical level timeout from 5 seconds to 30 seconds.

## 0.4.0

This is a feature release.

* Add the capability for services to add an :alerts object to their status
  function's output which will be returned by the HTTP endpoints under the
  "active_alerts" key.

## 0.3.5

This is a maintenance / bugfix release.

* Follow standard exception conventions internally and return standard errors
  through the API.
* Many improvements to documentation, largely around proxying and the `simple`
  endpoint.
* Update slf4j-api dependency.

## 0.3.4
_Never released due to an automation issue_

## 0.3.3

This is a maintenance / bugfix release.

* Allow trapperkeeper's `stop` and `start` functions to be called on the status
  service without error by cleaning up context state manually.

## 0.3.2

This is a maintenance / bugfix release.

* Cease enforcing semver when pulling versions from artifacts. This ended up
  being too restrictive and tk-status did not actually depend on or use the
  semver versions.

## 0.3.1

This is a maintenance / bugfix release.

* Exclude the obsolete servlet-api dependency from ring-defaults, to avoid
  classpath issues with multiple copies of the servlet API in downstream
  projects.

## 0.3.0

This is a feature release.

* Adds a status callback to the status service itself, to give us a place to
  expose status information that is common to all TK apps.
* [TK-321](https://tickets.puppetlabs.com/browse/TK-321) - add memory/heap
  usage metrics to status callback
* [TK-322](https://tickets.puppetlabs.com/browse/TK-322) - add process uptime
  to status callback
