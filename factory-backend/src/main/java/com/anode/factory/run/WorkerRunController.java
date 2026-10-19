package com.anode.factory.run;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
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
    private final RunBundles bundles;
    private final RunProperties properties;
    private final JsonMapper json;

    WorkerRunController(RunQueue queue, RunBundles bundles, RunProperties properties, JsonMapper json) {
        this.queue = queue;
        this.bundles = bundles;
        this.properties = properties;
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

    /** The work branch so far, for the run to start from; none until a code-writing run succeeded. */
    @GetMapping(value = "/{runId}/bundle", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<byte[]> bundle(@PathVariable UUID runId, Authentication worker) {
        Run run = queue.running(runId, worker(worker));
        return bundles.latest(run.workOrderId()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** The work of an agent run, uploaded before it completes; it counts once the run succeeded. */
    @PutMapping(value = "/{runId}/bundle", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<Void> upload(@PathVariable UUID runId, @RequestBody byte[] bundle, Authentication worker) {
        Run run = queue.running(runId, worker(worker));
        if (run.kind() != RunKind.AGENT) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only agent runs write code");
        }
        if (bundle.length > properties.maxBundleSize().toBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "A bundle is at most " + properties.maxBundleSize());
        }
        bundles.save(runId, bundle);
        return ResponseEntity.noContent().build();
    }
}
