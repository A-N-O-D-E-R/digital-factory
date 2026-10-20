package com.anode.factory.worker;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The workers' reports of themselves; the answer tells a worker whether to drain. */
@RestController
@PreAuthorize("hasRole('WORKER')")
class WorkerHeartbeatController {

    record Heartbeat(String version, int slots, int busy) {}

    private final WorkerRegistry registry;

    WorkerHeartbeatController(WorkerRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/internal/workers/heartbeat")
    Map<String, Boolean> heartbeat(@RequestBody Heartbeat heartbeat, Authentication worker) {
        return Map.of("draining", registry.heartbeat(UUID.fromString(worker.getName()), heartbeat.version(),
                heartbeat.slots(), heartbeat.busy()));
    }
}
