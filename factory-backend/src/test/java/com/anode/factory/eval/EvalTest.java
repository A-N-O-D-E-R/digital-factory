package com.anode.factory.eval;

import static com.anode.factory.FakeWorker.Outputs.acceptance;
import static com.anode.factory.FakeWorker.Outputs.analysis;
import static com.anode.factory.FakeWorker.Outputs.classification;
import static com.anode.factory.FakeWorker.Outputs.implementation;
import static com.anode.factory.FakeWorker.Outputs.review;
import static com.anode.factory.Users.admin;
import static com.anode.factory.Users.developer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.anode.factory.FakeWorker;
import com.anode.factory.GitHubWebhook;
import com.anode.factory.IntegrationTest;
import com.anode.factory.TestRepository;
import com.anode.factory.forge.FakeForge;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Autonomy earned: fixtures through the real pipeline, judged by hidden tests, then a merge without a human. */
@IntegrationTest
class EvalTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** Every kind of evidence, a final gate unless autonomous, merge once the forge's checks pass. */
    static final String AUTOPILOT = """
            { "start": "classify", "steps": [
              { "id": "classify", "kind": "agent", "roles": ["classifier"], "next": [{ "to": "analyze" }] },
              { "id": "analyze", "kind": "agent", "roles": ["analyst"],
                "next": [{ "when": { "fact": "analysis.valid", "equals": false }, "to": "@blocked" }, { "to": "specify" }] },
              { "id": "specify", "kind": "agent", "roles": ["specifier"], "next": [{ "to": "implement" }] },
              { "id": "implement", "kind": "agent", "roles": ["implementer"], "next": [{ "to": "verify" }] },
              { "id": "verify", "kind": "factory", "action": "run-tests",
                "next": [{ "when": { "fact": "tests.passed", "equals": false }, "to": "implement", "maxVisits": 1 },
                         { "to": "secrets" }] },
              { "id": "secrets", "kind": "factory", "action": "secret-scan",
                "next": [{ "when": { "fact": "secrets.found", "equals": true }, "to": "implement", "maxVisits": 1 },
                         { "to": "review" }] },
              { "id": "review", "kind": "agent", "roles": ["reviewer"],
                "next": [{ "when": { "fact": "verdict", "equals": "REVISE" }, "to": "implement", "maxVisits": 1 },
                         { "to": "push" }] },
              { "id": "push", "kind": "factory", "action": "push-branch", "next": [{ "to": "open-pr" }] },
              { "id": "open-pr", "kind": "factory", "action": "open-draft-pr", "next": [{ "to": "final-gate" }] },
              { "id": "final-gate", "kind": "gate", "gate": "final", "when": { "fact": "autonomy.allows", "equals": false },
                "next": [{ "when": { "fact": "approval", "equals": "REJECTED" }, "to": "@cancelled" }, { "to": "ready" }] },
              { "id": "ready", "kind": "factory", "action": "mark-pr-ready", "next": [{ "to": "watch" }] },
              { "id": "watch", "kind": "factory", "action": "watch-pr",
                "next": [{ "when": { "fact": "pr.event", "equals": "merged" }, "to": "@done" },
                         { "when": { "fact": "pr.event", "equals": "closed" }, "to": "@cancelled" },
                         { "when": { "allOf": [{ "fact": "pr.event", "equals": "checks_passed" },
                                               { "fact": "autonomy.allows", "equals": true }] }, "to": "merge", "maxVisits": 1 },
                         { "to": "watch", "maxVisits": 10 }] },
              { "id": "merge", "kind": "factory", "action": "merge", "next": [{ "to": "watch" }] }
            ] }""";

    @Autowired MockMvcTester mvc;
    @Autowired FakeForge forge;
    @Autowired Evals evals;
    final JsonMapper json = JsonMapper.builder().build();
    FakeWorker worker;

    @BeforeEach
    void setUp() {
        TestRepository.register(mvc);
        worker = new FakeWorker(mvc, "eval-worker-" + COUNTER.incrementAndGet());
        assertThat(mvc.post().uri("/api/workflows/autopilot/versions").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"definition\":" + AUTOPILOT + ",\"note\":\"test\"}"))
                .hasStatus(201);
        while (!worker.hasNothingToClaim()) {
            // other tests' leftovers: their leases will expire
        }
    }

    JsonNode get(String uri, Object... vars) {
        return json.readTree(mvc.get().uri(uri, vars).with(admin()).exchange().getResponse().getContentAsByteArray());
    }

    /** A competent factory: refuses the vague fixture at analysis, does the rest right. */
    void answer(FakeWorker.Job job) {
        boolean vague = job.input().get("workOrder").get("title").asString().contains("better");
        assertThat(job.input().has("judge")).isEqualTo(job.task().equals("judge"));
        worker.complete(job, switch (job.task()) {
            case "classifier" -> classification("DOCUMENTATION", "LOW");
            case "analyst" -> analysis(!vague, null);
            case "specifier" -> acceptance();
            case "implementer" -> implementation();
            case "run-tests" -> """
                    {"diff":{"files":1,"insertions":3,"deletions":0},
                     "tests":{"total":4,"failed":0,"errors":0,"skipped":0},
                     "acceptance":{"total":1,"failed":0,"errors":0,"skipped":0}}""";
            case "secret-scan" -> "{\"findings\":[]}";
            case "reviewer" -> review("PASS");
            case "judge" -> {
                assertThat(job.input().get("judge").get("command").asString()).isEqualTo("node --test hidden/usage.test.js");
                yield "{\"tests\":{\"total\":3,\"failed\":0,\"errors\":0,\"skipped\":0}}";
            }
            case "push-branch" -> "{\"branch\":\"factory/" + job.input().get("workOrder").get("id").asString()
                    + "\",\"commit\":\"" + "d".repeat(40) + "\"}";
            default -> throw new AssertionError("unexpected task " + job.task());
        });
    }

    /** Answers jobs until {@code done}. */
    void work(BooleanSupplier done) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50)).until(() -> {
            var claimed = mvc.post().uri("/internal/runs/claim").header("Authorization", worker.bearer()).exchange();
            if (claimed.getResponse().getStatus() == 200) {
                JsonNode job = json.readTree(claimed.getResponse().getContentAsByteArray());
                answer(new FakeWorker.Job(job.get("runId").asString(), job.get("task").asString(),
                        job.get("attempt").asInt(), job.get("input")));
            }
            return done.getAsBoolean();
        });
    }

    @Test
    void anEvalRunMeasuresTheClassThenTheFactoryMergesItsWorkAlone() {
        // without measurements, even a granted class waits for a human
        assertThat(mvc.put().uri("/api/autonomy-policy/DOCUMENTATION").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"MERGE\",\"minSuccessRate\":0.9}"))
                .hasStatusOk();

        var started = mvc.post().uri("/api/eval-runs").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"workflow\":\"autopilot\"}").exchange();
        assertThat(started).hasStatus(201);
        String run = json.readTree(started.getResponse().getContentAsByteArray()).get("id").asString();
        assertThat(get("/api/work-orders").findValuesAsString("title")).doesNotContain("Document the options");

        work(() -> get("/api/eval-runs/{id}", run).get("finished").asBoolean());

        JsonNode results = get("/api/eval-runs/{id}", run).get("results");
        assertThat(results.findValuesAsString("status")).containsExactlyInAnyOrder("DONE", "BLOCKED");
        assertThat(results.findValues("passed")).allMatch(JsonNode::asBoolean);
        assertThat(forge.calls()).noneMatch(call -> call.contains("Document the options"));
        assertThat(get("/api/autonomy-policy").findParents("type").stream()
                .filter(p -> p.get("type").asString().equals("DOCUMENTATION")).findFirst().orElseThrow()
                .get("successRate").asDouble()).isEqualTo(1.0);

        // a real work order of the class: no human, merged once the forge's checks pass
        var created = mvc.post().uri("/api/work-orders").with(developer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"repository\":\"" + TestRepository.KEY + "\",\"title\":\"Document the flags\","
                        + "\"description\":\"d\",\"workflow\":\"autopilot\"}")
                .exchange();
        UUID id = UUID.fromString(json.readTree(created.getResponse().getContentAsByteArray()).get("id").asString());
        work(() -> "watch".equals(get("/api/work-orders/{id}", id).get("workOrder").path("currentStep").asString()));
        JsonNode facts = get("/api/work-orders/{id}", id).get("workOrder").get("facts");
        long number = facts.get("pr.number").asLong();
        assertThat(facts.get("autonomy.allows").asBoolean()).isTrue();
        assertThat(get("/api/work-orders/{id}", id).get("approvals")).isEmpty();
        assertThat(forge.calls()).contains("markReady #" + number).doesNotContain("merge #" + number + " Document the flags");

        GitHubWebhook.deliver(mvc, "check_suite", GitHubWebhook.checksCompleted(number, "success"));
        await().atMost(Duration.ofSeconds(10)).until(() -> forge.calls().contains("merge #" + number + " Document the flags"));
        GitHubWebhook.deliver(mvc, "pull_request", GitHubWebhook.closed(number, true));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> "DONE".equals(get("/api/work-orders/{id}", id).get("workOrder").get("status").asString()));
    }
}
