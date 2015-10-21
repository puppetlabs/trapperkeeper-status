# Trapperkeeper Status Service

[![Build Status](https://travis-ci.org/puppetlabs/trapperkeeper-status.svg)](https://travis-ci.org/puppetlabs/trapperkeeper-status)

[![Clojars Project](http://clojars.org/puppetlabs/trapperkeeper-status/latest-version.svg)](http://clojars.org/puppetlabs/trapperkeeper-status)

A Trapperkeeper service that provides a web endpoint for getting status
information about a running Trapperkeeper application.

Other Trapperkeeper services may register a status callback function with the
Status Service, returning any kind of status information that is relevant to
the consuming service.  The Status Service will make this information available
via HTTP, in a consistent, consolidated format.  This makes it possible for users
to automate monitoring and other tasks around the system.

For more information, please see the [documentation](./documentation).

## Support

To file a bug, please open a Github issue against this project. Bugs and PRs are
addressed on a best-effort basis. Puppet Labs does not guarantee support for
this project.

## License

Copyright © 2015 Puppet Labs
