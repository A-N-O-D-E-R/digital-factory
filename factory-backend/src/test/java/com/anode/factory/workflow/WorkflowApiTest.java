package com.anode.factory.workflow;

import static com.anode.factory.Users.admin;
import static com.anode.factory.Users.developer;
import static com.anode.factory.Users.viewer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.anode.factory.IntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
class WorkflowApiTest {

    @Autowired MockMvcTester mvc;

    MvcTestResult validate(String definition) {
        return mvc.post().uri("/api/workflows/validate").with(developer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(definition).exchange();
    }

    void assertRefused(String definition, String expectedError) {
        assertThat(validate(definition)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.valid", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying("$.errors", errors -> assertThat(errors).asArray()
                        .anySatisfy(error -> assertThat(error).asString().contains(expectedError)));
    }

    static String linear(String steps) {
        return """
                { "start": "implement", "steps": [ %s ] }""".formatted(steps);
    }

    static final String IMPLEMENT = """
            { "id": "implement", "kind": "agent", "roles": ["implementer"], "next": [{ "to": "verify" }] }""";
    static final String VERIFY = """
            { "id": "verify", "kind": "factory", "action": "run-tests",
              "next": [{ "when": { "fact": "tests.passed", "equals": false }, "to": "implement", "maxVisits": 3 },
                       { "to": "push" }] }""";
    static final String PUSH = """
            { "id": "push", "kind": "factory", "action": "push-branch", "next": [{ "to": "@done" }] }""";

    @Test
    void theBuiltInWorkflowIsInstalledAndValid() {
        assertThat(mvc.get().uri("/api/workflows/default").with(viewer())).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.version", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.definition.start", s -> assertThat(s).isEqualTo("classify"));
        assertThat(validate(WorkflowCatalog.builtIn())).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.errors", e -> assertThat(e).asArray().isEmpty());
    }

    @Test
    void aMinimalValidWorkflow() {
        assertThat(validate(linear(IMPLEMENT + "," + VERIFY + "," + PUSH))).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.valid", v -> assertThat(v).isEqualTo(true));
    }

    tools.jackson.databind.JsonNode dryRun(String definition, String learnt) {
        MvcTestResult result = mvc.post().uri("/api/workflows/dry-run").with(developer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"definition\":" + definition + ",\"facts\":{},\"learnt\":" + learnt + "}").exchange();
        assertThat(result).hasStatusOk();
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    void aDryRunShowsThePathGivenWhatEachVisitWouldLearn() {
        String workflow = linear(IMPLEMENT + "," + VERIFY + "," + PUSH);
        var fixedOnSecondTry = dryRun(workflow, "{\"verify\":[{\"tests.passed\":false},{\"tests.passed\":true}]}");
        assertThat(fixedOnSecondTry.get("result").get("outcome").asString()).isEqualTo("@done");
        assertThat(fixedOnSecondTry.get("result").get("path").findValuesAsString("step"))
                .containsExactly("implement", "verify", "implement", "verify", "push");

        var neverFixed = dryRun(workflow, "{\"verify\":[{\"tests.passed\":false}]}");
        assertThat(neverFixed.get("result").get("outcome").asString()).isEqualTo("@blocked");
        assertThat(neverFixed.get("result").get("path").size()).isEqualTo(8); // 4 visits of each, the bound is 3
        assertThat(neverFixed.get("result").get("path").get(7).get("reason").asString()).isEqualTo("taken 3 times already");

        var invalid = dryRun(linear(IMPLEMENT), "{}");
        assertThat(invalid.get("valid").asBoolean()).isFalse();
        assertThat(invalid.get("result").isNull()).isTrue();
    }

    @Nested
    class Refused {

        @Test
        void schemaViolations() {
            assertRefused(linear("""
                    { "id": "implement", "kind": "robot", "next": [{ "to": "@done" }] }"""), "kind");
        }

        @Test
        void unknownTargets() {
            assertRefused(linear(IMPLEMENT.replace("verify", "nowhere")), "unknown step 'nowhere'");
        }

        @Test
        void unknownRoles() {
            assertRefused(linear(IMPLEMENT.replace("implementer", "wizard") + "," + VERIFY + "," + PUSH),
                    "unknown agent role 'wizard'");
        }

        @Test
        void unreachableSteps() {
            assertRefused(linear(IMPLEMENT + "," + VERIFY + "," + PUSH + """
                    , { "id": "orphan", "kind": "factory", "action": "comment-ticket", "next": [{ "to": "@done" }] }"""),
                    "step 'orphan' can never be reached");
        }

        @Test
        void unboundedLoops() {
            assertRefused(linear(IMPLEMENT + "," + VERIFY.replace(", \"maxVisits\": 3", "") + "," + PUSH),
                    "is unbounded");
        }

        @Test
        void publishingUntestedCode() {
            assertRefused(linear(IMPLEMENT.replace("verify", "push") + "," + PUSH),
                    "without the change being tested");
        }

        @Test
        void mergingWithoutTheAutonomyPolicy() {
            String merge = """
                    , { "id": "merge", "kind": "factory", "action": "merge", "next": [{ "to": "@done" }] }""";
            assertRefused(linear(IMPLEMENT + "," + VERIFY + "," + PUSH.replace("@done", "merge") + merge),
                    "step 'push' next[0] leads to a merge without requiring");
            String guarded = PUSH.replace("[{ \"to\": \"@done\" }]", "[{ \"when\": { \"allOf\": [{ \"fact\": "
                    + "\"autonomy.allows\", \"equals\": true }] }, \"to\": \"merge\" }, { \"to\": \"@done\" }]");
            assertThat(validate(linear(IMPLEMENT + "," + VERIFY + "," + guarded + merge))).hasStatusOk().bodyJson()
                    .hasPathSatisfying("$.valid", v -> assertThat(v).isEqualTo(true));
        }

        @Test
        void aConditionalLastTransition() {
            assertRefused(linear("""
                    { "id": "implement", "kind": "agent", "roles": ["implementer"],
                      "next": [{ "when": { "fact": "tests.passed", "equals": true }, "to": "@done" }] }"""),
                    "give the step an unconditional way out");
        }

        @Test
        void checksAtOnceProveTheChangeWhenTheTestsAreAmongThem() {
            String checks = """
                    { "id": "verify", "kind": "factory", "actions": ["run-tests", "secret-scan"], "next": [{ "to": "push" }] }""";
            assertThat(validate(linear(IMPLEMENT + "," + checks + "," + PUSH))).hasStatusOk().bodyJson()
                    .hasPathSatisfying("$.valid", v -> assertThat(v).isEqualTo(true));
            assertRefused(linear(IMPLEMENT + "," + checks.replace("\"run-tests\", ", "\"push-branch\", ") + "," + PUSH), "actions");
        }

        @Test
        void aDisabledTestStepProvesNothing() {
            assertRefused(linear(IMPLEMENT + "," + VERIFY.replace("\"action\": \"run-tests\",", "\"action\": \"run-tests\", \"enabled\": false,")
                    + "," + PUSH), "without the change being tested");
        }

        @Test
        void unknownFacts() {
            assertRefused(linear("""
                    { "id": "implement", "kind": "agent", "roles": ["implementer"],
                      "next": [{ "when": { "fact": "moon.phase", "equals": "full" }, "to": "@done" }, { "to": "@blocked" }] }"""),
                    "fact");
        }
    }

    @Nested
    class Publishing {

        MvcTestResult publish(String name, String definition, String note, org.springframework.test.web.servlet.request.RequestPostProcessor user) {
            String body = "{ \"definition\": " + definition + (note == null ? "" : ", \"note\": \"" + note + "\"") + " }";
            return mvc.post().uri("/api/workflows/{name}/versions", name).with(user).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        }

        @Test
        void versionsAreNumberedAndKept() {
            String v1 = linear(IMPLEMENT + "," + VERIFY + "," + PUSH);
            assertThat(publish("quick", v1, "first", admin())).hasStatus(201).bodyJson()
                    .hasPathSatisfying("$.version", v -> assertThat(v).isEqualTo(1));
            assertThat(publish("quick", v1.replace("\"maxVisits\": 3", "\"maxVisits\": 5"), "more retries", admin()))
                    .hasStatus(201).bodyJson().hasPathSatisfying("$.version", v -> assertThat(v).isEqualTo(2));

            assertThat(mvc.get().uri("/api/workflows/quick/versions/1").with(viewer())).hasStatusOk().bodyJson()
                    .hasPathSatisfying("$.note", n -> assertThat(n).isEqualTo("first"));
            assertThat(mvc.get().uri("/api/workflows/quick/versions/2").with(viewer())).hasStatusOk().bodyJson()
                    .hasPathSatisfying("$.diff", d -> assertThat(d).asArray()
                            .contains("-       \"maxVisits\" : 3", "+       \"maxVisits\" : 5"));
            assertThat(mvc.get().uri("/api/workflows/quick").with(viewer())).hasStatusOk().bodyJson()
                    .hasPathSatisfying("$.version", v -> assertThat(v).isEqualTo(2))
                    .hasPathSatisfying("$.createdBy", c -> assertThat(c).isEqualTo("ada"));
        }

        @Test
        void anInvalidDefinitionIsRefusedWithItsErrors() {
            assertThat(publish("broken", linear(IMPLEMENT.replace("verify", "push") + "," + PUSH), "oops", admin()))
                    .hasStatus(422).bodyJson()
                    .hasPathSatisfying("$.errors", e -> assertThat(e).asArray()
                            .anySatisfy(error -> assertThat(error).asString().contains("without the change being tested")));
            assertThat(mvc.get().uri("/api/workflows/broken").with(viewer())).hasStatus(404);
        }

        @Test
        void aNoteIsRequired() {
            assertThat(publish("nonote", linear(IMPLEMENT + "," + VERIFY + "," + PUSH), null, admin())).hasStatus(400);
        }

        @Test
        void onlyAdminsPublish() {
            assertThat(publish("dev-made", linear(IMPLEMENT + "," + VERIFY + "," + PUSH), "x", developer())).hasStatus(403);
        }
    }
}
