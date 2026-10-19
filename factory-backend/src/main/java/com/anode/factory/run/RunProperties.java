package com.anode.factory.run;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Execution of runs by the workers. */
@ConfigurationProperties("factory.runs")
public record RunProperties(
        /* attempts of a run before its step fails (invalid output, worker error, expired lease) */
        Integer maxAttempts,
        /* how long a claimed run stays a worker's without a heartbeat */
        Duration lease) {

    public RunProperties {
        maxAttempts = maxAttempts == null ? 2 : maxAttempts;
        lease = lease == null ? Duration.ofMinutes(5) : lease;
    }
}
