## Status Query API, v1

You can query for status information about services running in your application
by making an HTTP request to the `/status` endpoint.

## Status Detail Level

When querying for service status, you may optionally request a specific level
of detail to be returned.  The valid levels are:

* `"critical"` : returns only the bare minimum amount of status information for
  each service.  Intended to return very quickly and to be suitable for use
  cases like health checks for a load balancer.
* `"info"` : typically returns a bit more info than the `"critical"` level would,
  for each service.  The specific data that is returned will depend on the
  implementation details of the services in your application, but should generally
  be data that is useful for a human to get a quick impression of the health / status
  of each service.
* `"debug"` : this level can be used to request very detailed status information
  about a service, typically used by a human for debugging.  Requesting this
  level of status information may be significantly more expensive than the lower
  levels, depending on the service.  A common use case would be for a service to
  provide some detailed aggregate metrics about the performance or resource
  usage of its subsystems.

The information returned for any service at each increasing level of detail should
 be additive; in other words, `"info"` should return the same data structure as `"critical"`,
 but may add additional data in the `status` field.  Likewise, `"debug"` should
 return the same data structure as `"info"`, but may add additional information
 in the `status` field.

## `GET /status/v1/services`

This will return status information for all registered services in an application.

### URL Parameters

* `level`: Optional.  A JSON String from among the legal
[Status Detail Levels](#status-detail-level) listed above.  Status information for
all registered services will be provided at the requested level of detail.  If
not provided, the default level is `"info"`.

### Response Format

The response format will be a JSON _Object_, which will look something like this:

    {<service-name>: {
        "service_version": <service-version>,
        "service_status_version": <service-status-version>,
        "detail_level": <detail-level>,
        "state": <service-state>,
        "status": <any>
        },
     <service-name>: {
        ...
        },
    ...
    }

For detailed information, please see the [Wire Format Specification](./wire-formats.md).

NOTE: If any services in your application have registered more than one
supported status format version this endpoint will *always* return the latest
format.  Therefore, if you need to ensure backward compatibility across
upgrades of your application, you should consider using the
[`/services/<service-name>`](#get-statusv1servicesservice-name) endpoint
(which returns status info for a single service and can take a query parameter
specifying status version), rather than the `/services` endpoint (which
aggregates status for all registered services).

### Examples

Using `curl` from localhost:

Get the service status of all registered services in an application:

    curl -k https://localhost:8000/status/v1/services

    {
        "puppet-server": {
            "detail_level": "info",
            "state": "running",
            "service_status_version": 1,
            "service_version": "1.0.9-SNAPSHOT",
            "status": {
                "bar": "bar",
                "foo": "foo"
            }
        },

        "other-service": {
            "detail_level": "info",
            "state": "running",
            "service_status_version": 2,
            "service_version": "0.0.1-SNAPSHOT",
            "status": {
                "baz": [1, 2, 3],
                "bang": {"key": "value"}
            }
        }
    }

Get the service status of all registered services in an application, at a
 specified level of detail:

    curl -k "https://localhost:8140/status/v1/services?level=critical"

    {
        "puppet-server": {
            "detail_level": "critical",
            "state": "running",
            "service_status_version": 1,
            "service_version": "1.0.9-SNAPSHOT",
            "status": null
        },

        "other-service": {
            "detail_level": "info",
            "state": "running",
            "service_status_version": 2,
            "service_version": "0.0.1-SNAPSHOT",
            "status": null
        }
    }

## `GET /status/v1/services/<service-name>`

This will return status information for a single, specified service from the running
application.

### URL Parameters

* `level`: Optional.  A JSON String from among the legal
[Status Detail Levels](#status-detail-level) listed above.  Status information for
the requested service will be provided at the requested level of detail.  If
not provided, the default level is `"info"`.

* `service_status_version`: Optional.  A JSON integer specifying the desired status
format version for the requested service.  If not provided, defaults to the latest
available status format version for the service.

### Response Format

The response format will be a JSON _Object_ with a single entry,
which will look something like this:

    {<service-name>: {
        "service_version": <service-version>,
        "service_status_version": <service-status-version>,
        "detail_level": <detail-level>,
        "state": <service-state>,
        "status": <any>
        }
    }

For detailed information, please see the [Wire Format Specification](./wire-formats.md).

### Examples

Using `curl` from localhost:

Get the service status for a specified service in the application:

    curl -k https://localhost:8000/status/v1/services/other-service

    {
        "other-service": {
            "detail_level": "info",
            "state": "running",
            "service_status_version": 2,
            "service_version": "0.0.1-SNAPSHOT",
            "status": {
                "baz": [1, 2, 3],
                "bang": {"key": "value"}
            }
        }
    }

Get the service status for a specified service, using a specific status format
version:

    curl -k "https://localhost:8140/status/v1/services/other-service?service_status_version=1"

    {
        "other-service": {
            "detail_level": "info",
            "state": "running",
            "service_status_version": 1,
            "service_version": "0.0.1-SNAPSHOT",
            "status": {
                "oldbaz": 123,
                "bang": {"key": "value"}
            }
        }
    }

Get the service status for a specified service, using a specific status format
version and a specific detail level:

    curl -k "https://localhost:8140/status/v1/services/other-service?service_status_version=2&level=debug"

    {
        "other-service": {
            "detail_level": "debug",
            "state": "running",
            "service_status_version": 2,
            "service_version": "0.0.1-SNAPSHOT",
            "status": {
                "baz": [1, 2, 3],
                "bang": {"key": "value"},
                "extra_debugging_info": [4, 5, 6]
            }
        }
    }
