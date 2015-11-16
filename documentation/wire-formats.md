## Status Wire Format - Version 1

Service status information is represented as JSON.  Unless otherwise noted, `null`
is not allowed anywhere in the status data.

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

The response is a JSON _Object_.  All of the keys are service names, and all of the
values are maps containing status information about that service.

`<service-name>` is a String.

`<service-version>` is a String that complies with the
[Semantic Versioning Specification, v2.0.0](http://semver.org/spec/v2.0.0.html).
It indicates the version number of each service that is providing status information.

`<service-status-version>` is an Integer, specifying the format version of the status
 information for the particular service.  Any individual service may version its
 status formats independently from other services; this means that if a new version
 of a service is released, and it has new status information available, it may
 provide both a version `1` and a version `2` of its status format.  It is possible to
 query for the status information of an individual service and include a specific
 format version as part of the query, which provides a way for individual services to
 make backward compatibility guarantees about the format of their status data as it
 evolves over time.  See the docs on the
 [`/services/<service-name` endpoint](./query-api.md#get-statusv1servicesservice-name)
 for more info.

`<detail-level>` is a String from the following enumeration: (`"critical"`, `"info"`,
 `"debug"`).  `"critical"` indicates that the data returned should be only system-critical
 status information (suitable for use in load balancing / monitoring situations).
 Services may provide slightly more detail at the `"info"` level.  Services may
 provide very detailed information, suitable for debugging, at the `"debug"` level.

`<service-state>` is a String from the following enumeration: (`"running"`, `"error"`,
`"starting"`, `"stopping"`, `"unknown"`).  `"unknown"` will be used when there
is a problem that is preventing the Status Service from getting accurate status
information from the specified service.

`<any>` may be any valid JSON object (including `null`).  The data supplied here
 is specific to the individual service that is reporting status.

## Errors

Error responses are formatted as a JSON _Object_:

    {
        "type": <error-type-string>,
        "message": <error-message-string>
    }

`<error-type-string>` is a String that serves as a unique identifier for the type
of error that occurred.  For example, "service-status-version-not-found".

`<error-message-string>` is a String with a descriptive message about the error
that occurred.  For example: "No status function with version 2 found for service puppet-server".

## Encoding

The entire status payload is expected to be valid JSON, which mandates UTF-8
encoding.
