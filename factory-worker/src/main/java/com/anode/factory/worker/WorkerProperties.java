package com.anode.factory.worker;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The worker's settings; its token is the one the factory issued when it was registered. */
@ConfigurationProperties("factory.worker")
public record WorkerProperties(
        String controlPlaneUrl,
        String token,
        /* runs executed at once */
        Integer slots,
        /* where runs clone their repository; each run's directory is deleted after it */
        Path workDir,
        /* between claims when the queue is empty */
        Duration pollInterval,
        /* well within the control plane's lease (factory.runs.lease) */
        Duration heartbeatInterval,
        /* the Docker network of the sandboxes: "none", or one whose only way out is the egress proxy */
        String network,
        /* given to sandboxes as HTTP(S)_PROXY when set */
        String egressProxy,
        /* given to agent sandboxes only */
        String anthropicApiKey,
        /* what the worker runs as (its image tag), shown in the worker registry */
        String image,
        /* the command of agent sandboxes; empty: the image's own (the runner) */
        List<String> agentCommand,
        /* limits of the sandboxes running no agent (tests, judge) */
        Duration evidenceTimeout,
        Double evidenceCpus,
        Integer evidenceMemoryMb) {

    public WorkerProperties {
        slots = slots == null ? 1 : slots;
        workDir = workDir == null ? Path.of(System.getProperty("java.io.tmpdir"), "factory-worker") : workDir;
        pollInterval = pollInterval == null ? Duration.ofSeconds(5) : pollInterval;
        heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(30) : heartbeatInterval;
        network = network == null ? "none" : network;
        agentCommand = agentCommand == null ? List.of() : agentCommand;
        evidenceTimeout = evidenceTimeout == null ? Duration.ofMinutes(30) : evidenceTimeout;
        evidenceCpus = evidenceCpus == null ? 2.0 : evidenceCpus;
        evidenceMemoryMb = evidenceMemoryMb == null ? 4096 : evidenceMemoryMb;
    }
}
