package com.anode.factory.run;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The workers' side of the queue; the authenticated worker is the JWT subject. */
@RestController
@RequestMapping("/internal/runs")
@PreAuthorize("hasRole('WORKER')")
class WorkerRunController {

    record Completion(JsonNode output, BigDecimal costUsd) {}

    record Failure(String error, BigDecimal costUsd) {}

    private final RunQueue queue;
    private final JsonMapper json;

    WorkerRunController(RunQueue queue, JsonMapper json) {
        this.queue = queue;
        this.json = json;
    }

    private static UUID worker(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    @PostMapping("/claim")
    ResponseEntity<RunQueue.Job> claim(Authentication worker) {
        return queue.claim(worker(worker)).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{runId}/heartbeat")
    Map<String, Boolean> heartbeat(@PathVariable UUID runId, Authentication worker) {
        return Map.of("cancelled", queue.heartbeat(runId, worker(worker)));
    }

    @PostMapping("/{runId}/complete")
    ResponseEntity<Void> complete(@PathVariable UUID runId, @RequestBody Completion completion, Authentication worker) {
        String output = completion.output() == null ? null : json.writeValueAsString(completion.output());
        queue.complete(runId, worker(worker), output, completion.costUsd());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runId}/fail")
    ResponseEntity<Void> fail(@PathVariable UUID runId, @RequestBody Failure failure, Authentication worker) {
        queue.fail(runId, worker(worker), failure.error(), failure.costUsd());
        return ResponseEntity.noContent().build();
    }
}
