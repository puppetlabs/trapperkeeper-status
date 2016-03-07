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
