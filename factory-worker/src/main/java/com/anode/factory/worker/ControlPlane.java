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
@HttpExchange("/internal/runs")
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

    record Completion(JsonNode output, BigDecimal costUsd) {}

    record Failure(String error, BigDecimal costUsd) {}

    /** No body when there is nothing to claim. */
    @PostExchange("/claim")
    ResponseEntity<Job> claim();

    @PostExchange("/{runId}/heartbeat")
    Map<String, Boolean> heartbeat(@PathVariable UUID runId);

    @PostExchange("/{runId}/credential")
    GitCredential credential(@PathVariable UUID runId);

    /** No body until a code-writing run of the work order succeeded. */
    @GetExchange("/{runId}/bundle")
    ResponseEntity<byte[]> bundle(@PathVariable UUID runId);

    @PutExchange(value = "/{runId}/bundle", contentType = "application/octet-stream")
    void upload(@PathVariable UUID runId, @RequestBody byte[] bundle);

    @PostExchange("/{runId}/complete")
    void complete(@PathVariable UUID runId, @RequestBody Completion completion);

    @PostExchange("/{runId}/fail")
    void fail(@PathVariable UUID runId, @RequestBody Failure failure);
}
