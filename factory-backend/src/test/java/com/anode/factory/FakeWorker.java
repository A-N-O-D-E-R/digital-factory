package com.anode.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A worker as the real one talks to the control plane: registered by an admin, then over /internal/runs. */
public final class FakeWorker {

    private final MockMvcTester mvc;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String token;
    private final String id;

    /** A claimed job. */
    public record Job(String runId, String task, int attempt, JsonNode input) {}

    public FakeWorker(MockMvcTester mvc, String name) {
        this.mvc = mvc;
        MvcTestResult registered = mvc.post().uri("/api/workers").with(Users.admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}").exchange();
        assertThat(registered).hasStatus(201);
        JsonNode body = json.readTree(registered.getResponse().getContentAsByteArray());
        this.token = body.get("token").asString();
        this.id = body.get("worker").get("id").asString();
    }

    public String id() {
        return id;
    }

    public String bearer() {
        return "Bearer " + token;
    }

    /** Claims the next job, failing the test if there is none. */
    public Job claim() {
        MvcTestResult result = mvc.post().uri("/internal/runs/claim").header("Authorization", bearer()).exchange();
        assertThat(result).as("a job to claim").hasStatus(200);
        JsonNode job = json.readTree(result.getResponse().getContentAsByteArray());
        return new Job(job.get("runId").asString(), job.get("task").asString(), job.get("attempt").asInt(),
                job.get("input"));
    }

    public boolean hasNothingToClaim() {
        return mvc.post().uri("/internal/runs/claim").header("Authorization", bearer()).exchange()
                .getResponse().getStatus() == 204;
    }

    public void complete(Job job, String outputJson) {
        assertThat(mvc.post().uri("/internal/runs/{id}/complete", job.runId()).header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"output\":" + outputJson + ",\"costUsd\":0.01,"
                        + "\"usage\":{\"model\":\"claude-test\",\"inputTokens\":1200,\"outputTokens\":80}}").exchange()).hasStatus(204);
    }

    public void fail(Job job, String error) {
        assertThat(mvc.post().uri("/internal/runs/{id}/fail", job.runId()).header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("error", error))).exchange()).hasStatus(204);
    }

    /** Canned valid outputs per task. */
    public static final class Outputs {
        public static String classification(String type, String risk) {
            return "{\"type\":\"" + type + "\",\"risk\":\"" + risk + "\",\"summary\":\"s\"}";
        }

        public static String analysis(boolean valid, Boolean reproduced) {
            return "{\"valid\":" + valid + ",\"reproduced\":" + reproduced
                    + ",\"risk\":\"LOW\",\"plan\":\"p\",\"acceptanceCriteria\":[\"c\"]}";
        }

        public static String implementation() {
            return "{\"summary\":\"done\",\"commitMessage\":\"fix: it\"}";
        }

        public static String acceptance() {
            return "{\"tests\":[{\"path\":\"acceptance/a.test.js\",\"content\":\"test\"}],\"command\":\"node --test acceptance\"}";
        }

        public static String review(String verdict) {
            return "{\"verdict\":\"" + verdict + "\",\"summary\":\"r\",\"comments\":[]}";
        }

        public static String tests(int files, int failed) {
            return "{\"diff\":{\"files\":" + files + ",\"insertions\":3,\"deletions\":1},"
                    + "\"tests\":{\"total\":10,\"failed\":" + failed + ",\"errors\":0,\"skipped\":0}}";
        }
    }
}
