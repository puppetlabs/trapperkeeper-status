# Status logging

TK-status can periodically log status data to a file in JSON format.

To take advantage of this you must do two things:

1. Enable logging through you TK app's configuration. For example, to log every 2 seconds, your configuration might look like this:

    ```
    status: {
        debug-logging: {
            interval-minutes: 5,
        }
    }
    ```

2. Create a logback `logger` in your logback configuration for the `puppetlabs.trapperkeeper.services.status.status-logging` namespace.
   For example, this `logger` and `appender` will send the status log messages to a file, manage rotating them, and keep them from taking up too much room
    ```
    <appender name="STATUS" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>/tmp/status.log</file>
        <append>true</append>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <!-- rollover daily -->
            <fileNamePattern>/status-%d{yyyy-MM-dd}.%i.log.zip</fileNamePattern>
            <!-- each file should be at most 10MB, keep 90 days worth of history, but at most 1GB total-->
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>90</maxHistory>
            <totalSizeCap>1GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <!-- note that this will only log the JSON message (%m) and a newline (%n)-->
            <pattern>%m%n</pattern>
        </encoder>
    </appender>

    <!-- without additivity="false", the status log messages will be sent to every other appender as well-->
    <logger name="puppetlabs.trapperkeeper.services.status.status-logging" additivity="false">
            <appender-ref ref="STATUS"/>
    </logger>
    ```
    A simpler config with no rotation would look like this:

    ```
    <appender name="STATUS" class="ch.qos.logback.core.FileAppender">
        <file>/tmp/status.log</file>
        <encoder>
            <pattern>%m%n</pattern>
        </encoder>
    </appender>

    <logger name="puppetlabs.trapperkeeper.services.status.status-logging" additivity="false">
        <appender-ref ref="STATUS"/>
    </logger>
    ```
