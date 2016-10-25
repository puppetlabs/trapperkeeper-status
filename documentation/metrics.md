## Metrics

It is common for services registering callbacks with the status service to include some basic metrics about the health
of the service.  These metrics are typically only available at the `:debug` status level, and should be limited to a
fairly small set of data that can be useful for debugging the service's performance or behavior.  We've also been, for now,
putting them underneath a key in the map called `"experimental"`, so that we can gather some feedback on UX before
committing to a long-term API / wire format for the data structure.

Read on to see how some debug metrics of this type are included with `trapperkeeper-status` itself; for an example of
how a downstream service might register it's own metrics, you can take a look at the
[JRuby metrics code in the pe-puppet-server-extensions repo.](https://github.com/puppetlabs/pe-puppet-server-extensions/blob/3531fa00ce20c99b662595569edc9ef3d1b4daaa/src/clj/puppetlabs/enterprise/services/jruby/pe_jruby_metrics_service.clj#L54-L58)

### JVM Metrics

In addition to metrics that downstream services may choose to make available via the status service, the status
service itself ships with some basic JVM metrics that can be useful for monitoring the process as a whole.

Here is an example of what the status service's own status callback returns at `:debug` level:

```json
    "status-service": {
        "active_alerts": [],
        "detail_level": "debug",
        "service_status_version": 1,
        "service_version": "0.5.0",
        "state": "running",
        "status": {
            "experimental": {
                "jvm-metrics": {
                    "file-descriptors": {
                        "max": 65536,
                        "used": 198
                    },
                    "gc-stats": {
                        "PS MarkSweep": {
                            "count": 5,
                            "total-time-ms": 657
                        },
                        "PS Scavenge": {
                            "count": 17,
                            "total-time-ms": 306
                        }
                    },
                    "heap-memory": {
                        "committed": 1111490560,
                        "init": 262144000,
                        "max": 1908932608,
                        "used": 612812056
                    },
                    "non-heap-memory": {
                        "committed": 265027584,
                        "init": 2555904,
                        "max": -1,
                        "used": 178038080
                    },
                    "start-time-ms": 1475685724906,
                    "up-time-ms": 25466
                }
            }
        }
    }

```

The most interesting part of the payload above is the data available in the "status" -> "experimental" -> "jvm-metrics"
map.  Here are some details about the fields available there:

* `heap-memory`: information about the JVM's heap memory usage; this mostly accounts for memory consumed by application code
** `committed`: the amount of memory that the operating system has allocated to the process
** `init`: the initial amount of memory that was allocated to the process at startup
** `max`: the maximum amount of memory that the process will request from the operating system before throwing an OOM error
** `used`: the amount of memory that is currently being used by the application
* `non-heap-memory`: same fields as for `heap-memory`, but this refers to native memory used by the JVM itself, along with any memory allocated by native libraries
* `file-descriptors`:
** `max`: the maximum number of file descriptors that this process is allowed to open
** `used`: the current number of file descriptors the process has open
* `gc-stats`: a map containing key garbage collection statistics for each of the different GC algorithms that are in use by this JVM.  The keys in this map are the GC algorithm names
** `count`: the number of executions of this GC algorithm
** `total-time-ms`: the cumulative number of milliseconds of CPU time that have been spent executing the garbage collections for this algorithm

### Logging metrics data

As of tk-status 0.6.0, there is a new configuration setting available that can be used to cause the status service to
periodically log debugging metrics data to a file.  For more information, see the [docs on status logging](./status-logging.md).