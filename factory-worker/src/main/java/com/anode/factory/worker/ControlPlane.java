package com.anode.factory.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;
import tools.jackson.databind.JsonNode;

/** The control plane's internal API, as the worker's credential allows it. */
@HttpExchange("/internal")
public interface ControlPlane {

    /** {@code kind}: AGENT, EVIDENCE or PUSH. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Job(UUID runId, UUID workOrderId, String kind, String task, int attempt, JsonNode input,
               JsonNode outputSchema, BigDecimal budgetUsd) {}

    record GitCredential(String cloneUrl, String username, String password, Instant expiresAt) {
        @Override
        public String toString() {
            return "GitCredential[" + cloneUrl + "]";
        }
    }

    /** What an agent run used; null for runs calling no model. */
    record Usage(String model, long inputTokens, long outputTokens) {}

    record Completion(JsonNode output, BigDecimal costUsd, Usage usage) {}

    record Failure(String error, BigDecimal costUsd, Usage usage) {}

    record Heartbeat(String version, int slots, int busy) {}

    /** What the worker is; whether it is draining (it claims nothing then). */
    @PostExchange("/workers/heartbeat")
    Map<String, Boolean> heartbeat(@RequestBody Heartbeat heartbeat);

    /** No body when there is nothing to claim (or the worker is draining). */
    @PostExchange("/runs/claim")
    ResponseEntity<Job> claim();

    @PostExchange("/runs/{runId}/heartbeat")
    Map<String, Boolean> heartbeat(@PathVariable UUID runId);

    @PostExchange("/runs/{runId}/credential")
    GitCredential credential(@PathVariable UUID runId);

    /** No body until a code-writing run of the work order succeeded. */
    @GetExchange("/runs/{runId}/bundle")
    ResponseEntity<byte[]> bundle(@PathVariable UUID runId);

    @PutExchange(value = "/runs/{runId}/bundle", contentType = "application/octet-stream")
    void upload(@PathVariable UUID runId, @RequestBody byte[] bundle);

    @PostExchange("/runs/{runId}/complete")
    void complete(@PathVariable UUID runId, @RequestBody Completion completion);

    @PostExchange("/runs/{runId}/fail")
    void fail(@PathVariable UUID runId, @RequestBody Failure failure);
}
