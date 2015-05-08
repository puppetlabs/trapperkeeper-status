## Trapperkeeper Status Service Documentation

* [Querying the Status Service](./query-api.md)
* [Wire Formats](./wire-formats.md)
* [Developer Documentation](./developers.md)

## What's Next?

Things that are not finished yet:

* The API; it should not be considered final yet,
  but it will have to be finalized before the first consuming
  service ships a version that includes this.  Please provide feedback!
* Improved error handling (e.g. if one service's status
  callback fails, that should not prevent us from returning
  status for other services: see TK-175).
* Support for a plain-text HTTP endpoint that proxies through
  to an HTTPS version using configurable certs; this is for
  use cases involving load balancers and other monitoring
  devices that don't provide sophisticated SSL configuration
  capabilities (see TK-176).
* Finalize internal schemas, improve JSON serialization code.
* Improve developer experience a bit; e.g. maybe provide a
  numeric representation of the detail levels to make it
  easier for service authors to add data to their status
  based on the detail level (see TK-217).