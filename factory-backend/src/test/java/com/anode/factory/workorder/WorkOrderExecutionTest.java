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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
        assertThat(done.get("runs").get(0).get("model").asString()).isEqualTo("claude-test");
        assertThat(done.get("runs").get(0).get("inputTokens").asLong()).isEqualTo(1200);

        String testsRun = done.get("runs").get(3).get("id").asString();
        var output = mvc.get().uri("/api/work-orders/{id}/runs/{run}", id, testsRun).with(developer()).exchange();
        assertThat(output).hasStatusOk().bodyJson().extractingPath("$.output.tests.total").isEqualTo(10);
        assertThat(mvc.get().uri("/api/work-orders/{id}/runs/{run}", id, testsRun).with(developer()).exchange())
                .bodyJson().extractingPath("$.logs").isEqualTo("[INFO] BUILD SUCCESS");
        assertThat(mvc.get().uri("/api/work-orders/{id}/runs/{run}", id, UUID.randomUUID()).with(developer()).exchange())
                .hasStatus(404);
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

    MvcTestResult putBundle(FakeWorker.Job job, String content) {
        return mvc.put().uri("/internal/runs/{id}/bundle", job.runId()).header("Authorization", worker.bearer())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(content.getBytes(StandardCharsets.UTF_8)).exchange();
    }

    MvcTestResult getBundle(FakeWorker.Job job) {
        return mvc.get().uri("/internal/runs/{id}/bundle", job.runId()).header("Authorization", worker.bearer())
                .exchange();
    }

    @Test
    void theWorkOfASucceededImplementerIsWhereTheNextRunsStart() {
        UUID id = create("mini");
        worker.complete(claim("classifier"), classification("BUG", "LOW"));
        awaitStep(id, "specify");
        FakeWorker.Job specify = claim("specifier");
        assertThat(specify.input().get("writesCode").asBoolean()).isFalse();
        worker.complete(specify, acceptance());
        awaitStep(id, "implement");

        FakeWorker.Job first = claim("implementer");
        assertThat(first.input().get("writesCode").asBoolean()).isTrue();
        assertThat(getBundle(first)).hasStatus(204);
        assertThat(putBundle(first, "first attempt")).hasStatus(204);
        worker.fail(first, "sandbox crashed");

        FakeWorker.Job second = claim("implementer");
        assertThat(getBundle(second)).as("a failed attempt's work is dropped").hasStatus(204);
        assertThat(putBundle(second, "second attempt")).hasStatus(204);
        worker.complete(second, implementation());
        awaitStep(id, "verify");

        FakeWorker.Job verify = claim("run-tests");
        assertThat(getBundle(verify)).hasStatus(200).hasBodyTextEqualTo("second attempt");
        assertThat(putBundle(verify, "evidence")).as("evidence runs write no code").hasStatus(403);
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
    void aRevisionThatChangesNothingIsSeen() {
        publish("no-progress", """
                { "start": "implement", "steps": [
                  { "id": "implement", "kind": "agent", "roles": ["implementer"], "next": [{ "to": "verify" }] },
                  { "id": "verify", "kind": "factory", "action": "run-tests",
                    "next": [{ "when": { "allOf": [{ "fact": "diff.unchanged", "equals": true }, { "fact": "tests.passed", "equals": false }] }, "to": "@blocked" },
                             { "when": { "fact": "tests.passed", "equals": false }, "to": "implement", "maxVisits": 5 },
                             { "to": "@done" }] }
                ] }""");
        String red = "{\"diff\":{\"files\":1,\"insertions\":1,\"deletions\":0},\"patch\":\"+a\","
                + "\"tests\":{\"total\":1,\"failed\":1,\"errors\":0,\"skipped\":0}}";
        UUID id = create("no-progress");
        worker.complete(claim("implementer"), implementation());
        awaitStep(id, "verify");
        worker.complete(claim("run-tests"), red);
        awaitStep(id, "implement");
        worker.complete(claim("implementer"), implementation());
        awaitStep(id, "verify");
        worker.complete(claim("run-tests"), red); // the same patch, still red
        JsonNode blocked = awaitStatus(id, "BLOCKED");
        assertThat(blocked.get("workOrder").get("facts").get("diff.unchanged").asBoolean()).isTrue();
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

    MvcTestResult rework(UUID id, String body) {
        return mvc.post().uri("/api/work-orders/{id}/rework", id).with(developer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    @Test
    void aBlockedWorkOrderIsReworkedFromAStepWithMoreInstructions() {
        UUID id = create("mini");
        assertThat(rework(id, "{\"instructions\":\"x\"}")).as("not blocked").hasStatus(409);
        worker.complete(claim("classifier"), classification("BUG", "LOW"));
        awaitStep(id, "specify");
        worker.complete(claim("specifier"), acceptance());
        awaitStep(id, "implement");
        worker.complete(claim("implementer"), implementation());
        awaitStep(id, "verify");
        worker.complete(claim("run-tests"), tests(0, 0));
        awaitStatus(id, "BLOCKED");

        assertThat(rework(id, "{\"instructions\":\"x\",\"step\":\"nope\"}")).hasStatus(422);
        assertThat(rework(id, "{\"instructions\":\" \"}")).hasStatus(400);
        assertThat(rework(id, "{\"instructions\":\"The fix belongs in Totals.round\",\"step\":\"implement\"}"))
                .hasStatus(204);

        FakeWorker.Job implement = claim("implementer");
        assertThat(implement.input().get("facts").get("rework.instructions").asString())
                .startsWith("The fix belongs in Totals.round");
        worker.complete(implement, implementation());
        awaitStep(id, "verify");
        worker.complete(claim("run-tests"), tests(2, 0));
        JsonNode done = awaitStatus(id, "DONE");
        assertThat(done.get("runs")).hasSize(6); // nothing before the reworked step ran again
    }

    @Test
    void securityWorkMajorBumpsAndLargeChangesAreHighRiskWhateverTheAgentsSay() {
        UUID security = create("mini");
        worker.complete(claim("classifier"), classification("SECURITY", "LOW"));
        assertThat(awaitStatus(security, "WAITING_FOR_HUMAN").get("workOrder").get("facts").get("risk").asString())
                .as("the plan gate of HIGH risk").isEqualTo("HIGH");

        UUID patch = create("mini");
        worker.complete(claim("classifier"), "{\"type\":\"DEPENDENCY\",\"risk\":\"LOW\",\"dependencyBump\":\"patch\",\"summary\":\"s\"}");
        awaitStep(patch, "specify");
        worker.complete(claim("specifier"), acceptance());
        awaitStep(patch, "implement");
        worker.complete(claim("implementer"), implementation());
        awaitStep(patch, "verify");
        worker.complete(claim("run-tests"), tests(25, 0)); // more than risk.highAboveFiles (20)
        JsonNode facts = awaitStatus(patch, "DONE").get("workOrder").get("facts");
        assertThat(facts.get("risk").asString()).isEqualTo("HIGH");
        assertThat(facts.get("autonomy.allows").asBoolean()).isFalse();
    }

    @Test
    void threeReviewersOfTheSameRoleAreThreeVerdictsAndTheMajorityDecides() {
        String panel = """
                { "start": "review", "steps": [
                  { "id": "review", "kind": "agent", "roles": ["reviewer", "reviewer", "reviewer"], "join": "majority",
                    "next": [{ "when": { "fact": "verdict", "equals": "PASS" }, "to": "@done" }, { "to": "@blocked" }] }
                ] }""";
        assertThat(mvc.post().uri("/api/workflows/panel/versions").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"definition\":" + panel + ",\"note\":\"test\"}")).hasStatus(201);
        UUID id = create("panel");
        FakeWorker.Job first = claim("reviewer");
        FakeWorker.Job second = claim("reviewer");
        FakeWorker.Job third = claim("reviewer");
        worker.complete(first, com.anode.factory.FakeWorker.Outputs.review("PASS"));
        worker.complete(second, com.anode.factory.FakeWorker.Outputs.review("REVISE"));
        assertThat(detail(id).get("workOrder").get("status").asString()).as("waits for the third").isEqualTo("RUNNING");
        worker.complete(third, com.anode.factory.FakeWorker.Outputs.review("PASS"));
        assertThat(awaitStatus(id, "DONE").get("workOrder").get("facts").get("verdict").asString()).isEqualTo("PASS");
    }

    @Test
    void onceClassifiedAWorkOrderGoesOnInTheWorkflowItsTypeIsMappedTo() {
        publish("routed", """
                { "start": "classify", "steps": [
                  { "id": "classify", "kind": "agent", "roles": ["classifier"], "next": [{ "to": "route" }] },
                  { "id": "route", "kind": "factory", "action": "select-workflow", "next": [{ "to": "@done" }] }
                ] }""");
        publish("docs-only", """
                { "start": "write", "steps": [
                  { "id": "write", "kind": "agent", "roles": ["implementer"], "next": [{ "to": "verify" }] },
                  { "id": "verify", "kind": "factory", "action": "run-tests", "next": [{ "to": "@done" }] }
                ] }""");
        var repositorySettings = mvc.put().uri("/api/settings?repository={r}", TestRepository.KEY).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"document\":{\"workflows\":{\"DOCUMENTATION\":\"docs-only\"}},\"reason\":\"docs are simpler\"}").exchange();
        assertThat(repositorySettings).hasStatusOk();
        try {
            UUID docs = create("routed");
            worker.complete(claim("classifier"), classification("DOCUMENTATION", "LOW"));
            JsonNode switched = awaitStep(docs, "write").get("workOrder");
            assertThat(switched.get("workflow").asString()).isEqualTo("docs-only");
            worker.complete(claim("implementer"), implementation());
            awaitStep(docs, "verify");
            worker.complete(claim("run-tests"), tests(1, 0));
            awaitStatus(docs, "DONE");

            UUID bug = create("routed");
            worker.complete(claim("classifier"), classification("BUG", "LOW"));
            assertThat(awaitStatus(bug, "DONE").get("workOrder").get("workflow").asString()).as("unmapped: stays").isEqualTo("routed");
        } finally {
            mvc.put().uri("/api/settings?repository={r}", TestRepository.KEY).with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"document\":{},\"reason\":\"test done\"}").exchange();
        }
    }

    void publish(String name, String definition) {
        assertThat(mvc.post().uri("/api/workflows/{name}/versions", name).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"definition\":" + definition + ",\"note\":\"test\"}"))
                .hasStatus(201);
    }

    @Test
    void aDisabledStepIsPassedThroughAndAStepCanAllowOneAttempt() {
        publish("trimmed", """
                { "start": "classify", "steps": [
                  { "id": "classify", "kind": "agent", "roles": ["classifier"], "enabled": false, "next": [{ "to": "implement" }] },
                  { "id": "implement", "kind": "agent", "roles": ["implementer"], "settings": { "maxAttempts": 1 },
                    "next": [{ "to": "@done" }] }
                ] }""");
        UUID id = create("trimmed");
        FakeWorker.Job implement = claim("implementer"); // no classifier: switched off
        assertThat(implement.input().get("settings").get("maxAttempts").asInt()).isEqualTo(1);
        worker.fail(implement, "sandbox crashed");
        assertThat(awaitStatus(id, "FAILED").get("workOrder").get("outcomeReason").asString())
                .contains("after 1 attempt(s)");
    }

    @Test
    void evidenceChecksRunAtOnceAndTheirFactsCombine() {
        publish("checks", """
                { "start": "implement", "steps": [
                  { "id": "implement", "kind": "agent", "roles": ["implementer"], "next": [{ "to": "prove" }] },
                  { "id": "prove", "kind": "factory", "actions": ["run-tests", "secret-scan"],
                    "next": [{ "when": { "fact": "secrets.found", "equals": true }, "to": "@blocked" },
                             { "when": { "fact": "tests.passed", "equals": true }, "to": "@done" }, { "to": "@failed" }] }
                ] }""");
        UUID id = create("checks");
        worker.complete(claim("implementer"), implementation());
        awaitStep(id, "prove");
        FakeWorker.Job first = worker.claim();
        FakeWorker.Job second = worker.claim();
        assertThat(List.of(first.task(), second.task())).as("both queued at once").containsExactlyInAnyOrder("run-tests", "secret-scan");
        for (FakeWorker.Job job : List.of(first, second)) {
            worker.complete(job, job.task().equals("run-tests") ? tests(2, 0) : "{\"findings\":[]}");
        }
        JsonNode facts = awaitStatus(id, "DONE").get("workOrder").get("facts");
        assertThat(facts.get("tests.passed").asBoolean()).isTrue();
        assertThat(facts.get("secrets.found").asBoolean()).isFalse();
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
    void workersReportThemselvesAndADrainedWorkerClaimsNothing() {
        var heartbeat = mvc.post().uri("/internal/workers/heartbeat").header("Authorization", worker.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.2.3\",\"image\":\"factory-worker:1.2.3\",\"slots\":4,\"busy\":1}")
                .exchange();
        assertThat(heartbeat).hasStatusOk().bodyJson().extractingPath("$.draining").isEqualTo(false);
        JsonNode workers = json.readTree(mvc.get().uri("/api/workers").with(admin()).exchange().getResponse()
                .getContentAsByteArray());
        JsonNode self = null;
        for (JsonNode w : workers) {
            if (w.get("id").asString().equals(worker.id())) {
                self = w;
            }
        }
        assertThat(self.get("version").asString()).isEqualTo("1.2.3");
        assertThat(self.get("slots").asInt()).isEqualTo(4);
        assertThat(self.get("image").asString()).isEqualTo("factory-worker:1.2.3");
        assertThat(self.get("lastHeartbeatAt").isNull()).isFalse();

        assertThat(mvc.post().uri("/api/workers/{id}/drain", worker.id()).with(admin()).with(csrf())).hasStatusOk();
        UUID id = create("mini");
        awaitStep(id, "classify");
        assertThat(worker.hasNothingToClaim()).as("drained").isTrue();
        assertThat(mvc.post().uri("/internal/workers/heartbeat").header("Authorization", worker.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.2.3\",\"slots\":4,\"busy\":0}"))
                .bodyJson().extractingPath("$.draining").isEqualTo(true);

        assertThat(mvc.post().uri("/api/workers/{id}/undrain", worker.id()).with(admin()).with(csrf())).hasStatusOk();
        claim("classifier");
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
