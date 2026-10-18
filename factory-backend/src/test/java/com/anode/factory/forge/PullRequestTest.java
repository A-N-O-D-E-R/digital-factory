package com.anode.factory.forge;

import static com.anode.factory.FakeWorker.Outputs.implementation;
import static com.anode.factory.FakeWorker.Outputs.tests;
import static com.anode.factory.Users.admin;
import static com.anode.factory.Users.developer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.anode.factory.FakeWorker;
import com.anode.factory.IntegrationTest;
import com.anode.factory.TestRepository;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** From the pushed branch to the merged PR on the (fake) forge; credentials lent to the workers. */
@IntegrationTest
class PullRequestTest {

    private static final AtomicInteger WORKERS = new AtomicInteger();

    /** implement -> verify -> push -> open draft PR -> final gate (unless autonomous) -> ready -> merge (if autonomous) */
    static final String SHIP = """
            { "start": "implement", "steps": [
              { "id": "implement", "kind": "agent", "roles": ["implementer"], "next": [{ "to": "verify" }] },
              { "id": "verify", "kind": "factory", "action": "run-tests",
                "next": [{ "when": { "fact": "tests.passed", "equals": false }, "to": "implement", "maxVisits": 1 },
                         { "to": "push" }] },
              { "id": "push", "kind": "factory", "action": "push-branch", "next": [{ "to": "open-pr" }] },
              { "id": "open-pr", "kind": "factory", "action": "open-draft-pr", "next": [{ "to": "final-gate" }] },
              { "id": "final-gate", "kind": "gate", "gate": "final", "when": { "fact": "autonomy.allows", "equals": false },
                "next": [{ "when": { "fact": "approval", "equals": "REJECTED" }, "to": "@cancelled" }, { "to": "ready" }] },
              { "id": "ready", "kind": "factory", "action": "mark-pr-ready",
                "next": [{ "when": { "fact": "autonomy.allows", "equals": true }, "to": "merge" }, { "to": "@done" }] },
              { "id": "merge", "kind": "factory", "action": "merge", "next": [{ "to": "@done" }] }
            ] }""";

    @Autowired MockMvcTester mvc;
    @Autowired FakeForge forge;
    final JsonMapper json = JsonMapper.builder().build();
    FakeWorker worker;

    @BeforeEach
    void setUp() {
        TestRepository.register(mvc);
        worker = new FakeWorker(mvc, "pr-worker-" + WORKERS.incrementAndGet());
        assertThat(mvc.post().uri("/api/workflows/ship/versions").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"definition\":" + SHIP + ",\"note\":\"test\"}"))
                .hasStatus(201);
        while (!worker.hasNothingToClaim()) {
            // other tests' leftovers: their leases will expire
        }
    }

    MvcTestResult create(String repository) {
        return mvc.post().uri("/api/work-orders").with(developer()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"repository":"%s","title":"Fix rounding","description":"d","workflow":"ship"}""".formatted(repository))
                .exchange();
    }

    JsonNode workOrder(UUID id) {
        return json.readTree(mvc.get().uri("/api/work-orders/{id}", id).with(developer()).exchange()
                .getResponse().getContentAsByteArray()).get("workOrder");
    }

    void awaitStep(UUID id, String step) {
        await().atMost(Duration.ofSeconds(10)).until(() -> step.equals(workOrder(id).path("currentStep").asString()));
    }

    MvcTestResult credential(FakeWorker as, FakeWorker.Job job) {
        return mvc.post().uri("/internal/runs/{id}/credential", job.runId()).header("Authorization", as.bearer()).exchange();
    }

    @Test
    void aProvenChangeIsPushedAndItsDraftPullRequestWaitsForAHumanBeforeBeingMarkedReady() {
        MvcTestResult created = create(TestRepository.KEY);
        assertThat(created).hasStatus(201);
        UUID id = UUID.fromString(json.readTree(created.getResponse().getContentAsByteArray()).get("id").asString());

        FakeWorker.Job implement = worker.claim();
        assertThat(implement.input().get("repository").get("testCommand").asString()).isEqualTo("mvn -B verify");
        assertThat(implement.input().get("workOrder").get("branch").asString()).isEqualTo("factory/" + id);
        assertThat(credential(worker, implement)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.cloneUrl", url -> assertThat(url).isEqualTo("https://github.test/acme/api.git"))
                .hasPathSatisfying("$.password", token -> assertThat(token).isEqualTo("read-token"));
        assertThat(credential(new FakeWorker(mvc, "pr-intruder-" + WORKERS.incrementAndGet()), implement)).hasStatus(404);
        worker.complete(implement, implementation());

        awaitStep(id, "verify");
        worker.complete(worker.claim(), tests(2, 0));
        awaitStep(id, "push");
        FakeWorker.Job push = worker.claim();
        assertThat(push.task()).isEqualTo("push-branch");
        assertThat(credential(worker, push)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.password", token -> assertThat(token).isEqualTo("write-token"));
        worker.complete(push, "{\"branch\":\"factory/" + id + "\",\"commit\":\"" + "a".repeat(40) + "\"}");

        await().atMost(Duration.ofSeconds(10))
                .until(() -> "WAITING_FOR_HUMAN".equals(workOrder(id).get("status").asString()));
        JsonNode facts = workOrder(id).get("facts");
        long number = facts.get("pr.number").asLong();
        assertThat(facts.get("pr.url").asString()).endsWith("/acme/api/pull/" + number);
        assertThat(facts.get("autonomy.allows").asBoolean()).isFalse();
        assertThat(forge.calls()).contains("openDraft acme/api factory/" + id + " -> main #" + number)
                .doesNotContain("markReady #" + number);

        String approval = json.readTree(mvc.get().uri("/api/work-orders/{id}", id).with(developer()).exchange()
                .getResponse().getContentAsByteArray()).get("approvals").get(0).get("id").asString();
        assertThat(mvc.post().uri("/api/work-orders/{id}/approvals/{a}", id, approval).with(developer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\",\"comment\":\"ok\"}"))
                .hasStatus(204);

        await().atMost(Duration.ofSeconds(10)).until(() -> "DONE".equals(workOrder(id).get("status").asString()));
        assertThat(forge.calls()).contains("markReady #" + number)
                .noneMatch(call -> call.startsWith("merge #" + number));
    }

    @Test
    void workOrdersAreOnlyCreatedOnRegisteredRepositories() {
        assertThat(create("github:acme/unknown")).hasStatus(422);
    }
}
