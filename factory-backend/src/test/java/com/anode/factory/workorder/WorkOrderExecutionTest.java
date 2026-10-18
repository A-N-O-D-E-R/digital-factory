package com.anode.factory.workorder;

import static com.anode.factory.FakeWorker.Outputs.acceptance;
import static com.anode.factory.FakeWorker.Outputs.classification;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Work orders driven through the API, a fake worker answering runs over the internal API. */
@IntegrationTest
class WorkOrderExecutionTest {

    private static final AtomicInteger WORKERS = new AtomicInteger();

    /** classify -> plan gate (HIGH risk only) -> specify -> implement -> verify (bounded retry) -> done */
    static final String MINI = """
            { "start": "classify", "steps": [
              { "id": "classify", "kind": "agent", "roles": ["classifier"], "next": [{ "to": "plan-gate" }] },
              { "id": "plan-gate", "kind": "gate", "gate": "plan", "when": { "fact": "risk", "equals": "HIGH" },
                "next": [{ "when": { "fact": "approval", "equals": "REJECTED" }, "to": "@cancelled" }, { "to": "specify" }] },
              { "id": "specify", "kind": "agent", "roles": ["specifier"], "next": [{ "to": "implement" }] },
              { "id": "implement", "kind": "agent", "roles": ["implementer"], "next": [{ "to": "verify" }] },
              { "id": "verify", "kind": "factory", "action": "run-tests",
                "next": [{ "when": { "fact": "diff.empty", "equals": true }, "to": "@blocked" },
                         { "when": { "fact": "tests.passed", "equals": false }, "to": "implement", "maxVisits": 1 },
                         { "to": "@done" }] }
            ] }""";

    @Autowired MockMvcTester mvc;
    final JsonMapper json = JsonMapper.builder().build();
    FakeWorker worker;

    @BeforeEach
    void setUp() {
        TestRepository.register(mvc);
        worker = new FakeWorker(mvc, "worker-" + WORKERS.incrementAndGet());
        mvc.post().uri("/api/workflows/mini/versions").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"definition\":" + MINI + ",\"note\":\"test\"}").exchange();
        drainQueue();
    }

    /** Other tests' leftovers must not be claimed by this one. */
    void drainQueue() {
        while (!worker.hasNothingToClaim()) {
            // claimed and abandoned: its lease will expire
        }
    }

    UUID create(String workflow) {
        var result = mvc.post().uri("/api/work-orders").with(developer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"repository":"%s","title":"Fix rounding","description":"Totals are off by a cent","workflow":"%s"}"""
                        .formatted(TestRepository.KEY, workflow))
                .exchange();
        assertThat(result).hasStatus(201);
        return UUID.fromString(json.readTree(result.getResponse().getContentAsByteArray()).get("id").asString());
    }

    JsonNode detail(UUID id) {
        var result = mvc.get().uri("/api/work-orders/{id}", id).with(developer()).exchange();
        assertThat(result).hasStatusOk();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    JsonNode awaitStep(UUID id, String step) {
        await().atMost(Duration.ofSeconds(10)).until(() -> step.equals(detail(id).get("workOrder").path("currentStep").asString()));
        return detail(id);
    }

    JsonNode awaitStatus(UUID id, String status) {
        await().atMost(Duration.ofSeconds(10)).until(() -> status.equals(detail(id).get("workOrder").get("status").asString()));
        return detail(id);
    }

    FakeWorker.Job claim(String expectedTask) {
        FakeWorker.Job job = worker.claim();
        assertThat(job.task()).isEqualTo(expectedTask);
        return job;
    }

    @Test
    void aWorkOrderGoesThroughItsWorkflowToDone() {
        UUID id = create("mini");
        assertThat(detail(id).get("workOrder").get("currentStep").asString()).isEqualTo("classify");

        worker.complete(claim("classifier"), classification("BUG", "LOW"));
        awaitStep(id, "specify");
        worker.complete(claim("specifier"), acceptance());
        awaitStep(id, "implement");
        worker.complete(claim("implementer"), implementation());
        awaitStep(id, "verify");
        worker.complete(claim("run-tests"), tests(2, 0));

        JsonNode done = awaitStatus(id, "DONE");
        assertThat(done.get("workOrder").get("facts").get("type").asString()).isEqualTo("BUG");
        assertThat(done.get("workOrder").get("facts").get("tests.passed").asBoolean()).isTrue();
        assertThat(done.get("runs")).hasSize(4);
    }

    @Test
    void acceptanceTestsAreHiddenFromTheImplementerButGivenToTheEvidenceRun() {
        UUID id = create("mini");
        worker.complete(claim("classifier"), classification("BUG", "LOW"));
        awaitStep(id, "specify");
        worker.complete(claim("specifier"), acceptance());
        awaitStep(id, "implement");

        FakeWorker.Job implement = claim("implementer");
        assertThat(implement.input().get("artifacts").has("specifier")).isFalse();
        assertThat(implement.input().get("artifacts").has("classifier")).isTrue();
        worker.complete(implement, implementation());
        awaitStep(id, "verify");

        FakeWorker.Job verify = claim("run-tests");
        assertThat(verify.input().get("artifacts").get("specifier").get("command").asString()).isEqualTo("node --test acceptance");
    }

    @Test
    void redTestsSendTheWorkBackToTheImplementerUntilTheBoundThenBlock() {
        UUID id = create("mini");
        worker.complete(claim("classifier"), classification("BUG", "LOW"));
        awaitStep(id, "specify");
        worker.complete(claim("specifier"), acceptance());
        awaitStep(id, "implement");
        worker.complete(claim("implementer"), implementation());
        awaitStep(id, "verify");
        worker.complete(claim("run-tests"), tests(2, 3));

        await().atMost(Duration.ofSeconds(10)).until(() -> detail(id).get("workOrder").get("currentVisit").asInt() == 2);
        assertThat(detail(id).get("workOrder").get("currentStep").asString()).isEqualTo("implement");
        worker.complete(claim("implementer"), implementation());
        awaitStep(id, "verify");
        worker.complete(claim("run-tests"), tests(2, 1));

        JsonNode blocked = awaitStatus(id, "BLOCKED");
        assertThat(blocked.get("workOrder").get("outcomeReason").asString()).contains("verify -> implement taken 1 times");
    }

    @Test
    void anEmptyDiffBlocks() {
        UUID id = create("mini");
        worker.complete(claim("classifier"), classification("BUG", "LOW"));
        awaitStep(id, "specify");
        worker.complete(claim("specifier"), acceptance());
        awaitStep(id, "implement");
        worker.complete(claim("implementer"), implementation());
        awaitStep(id, "verify");
        worker.complete(claim("run-tests"), tests(0, 0));

        awaitStatus(id, "BLOCKED");
    }

    @Test
    void aRiskyPlanWaitsForApproval() {
        UUID id = create("mini");
        worker.complete(claim("classifier"), classification("SECURITY", "HIGH"));
        JsonNode waiting = awaitStatus(id, "WAITING_FOR_HUMAN");
        String approvalId = waiting.get("approvals").get(0).get("id").asString();
        assertThat(worker.hasNothingToClaim()).isTrue();

        assertThat(mvc.post().uri("/api/work-orders/{id}/approvals/{a}", id, approvalId).with(developer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\",\"comment\":\"go\"}"))
                .hasStatus(204);

        awaitStep(id, "specify");
        assertThat(claim("specifier")).isNotNull();
    }

    @Test
    void aRejectedPlanCancelsTheWorkOrder() {
        UUID id = create("mini");
        worker.complete(claim("classifier"), classification("SECURITY", "HIGH"));
        String approvalId = awaitStatus(id, "WAITING_FOR_HUMAN").get("approvals").get(0).get("id").asString();

        mvc.post().uri("/api/work-orders/{id}/approvals/{a}", id, approvalId).with(developer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECTED\",\"comment\":\"no\"}").exchange();

        awaitStatus(id, "CANCELLED");
    }

    @Test
    void anInvalidOutputIsRetriedWithItsErrorsThenFailsTheWorkOrder() {
        UUID id = create("mini");
        worker.complete(claim("classifier"), "{\"type\":\"BUG\"}");

        FakeWorker.Job retry = claim("classifier");
        assertThat(retry.attempt()).isEqualTo(2);
        assertThat(retry.input().get("previousAttemptError").asString()).contains("risk");
        worker.complete(retry, "{\"type\":\"NOPE\",\"risk\":\"LOW\",\"summary\":\"s\"}");

        JsonNode failed = awaitStatus(id, "FAILED");
        assertThat(failed.get("workOrder").get("outcomeReason").asString()).contains("classifier INVALID_OUTPUT after 2 attempt(s)");
    }

    @Test
    void aWorkerErrorIsRetried() {
        UUID id = create("mini");
        worker.fail(claim("classifier"), "sandbox crashed");
        worker.complete(claim("classifier"), classification("BUG", "LOW"));
        awaitStep(id, "specify");
    }

    @Test
    void peopleCannotActAsWorkersAndRevokedWorkersAreLockedOut() {
        assertThat(mvc.post().uri("/internal/runs/claim").with(developer()).with(csrf())).hasStatus(403);
        assertThat(mvc.post().uri("/api/work-orders").header("Authorization", worker.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).hasStatus(403);

        assertThat(mvc.delete().uri("/api/workers/{id}", worker.id()).with(admin()).with(csrf())).hasStatus(204);
        assertThat(mvc.post().uri("/internal/runs/claim").header("Authorization", worker.bearer())).hasStatus(401);
    }
}
