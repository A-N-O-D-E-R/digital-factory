package com.anode.factory.prompt;

import static com.anode.factory.FakeWorker.Outputs.classification;
import static com.anode.factory.FakeWorker.Outputs.review;
import static com.anode.factory.Users.admin;
import static com.anode.factory.Users.developer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.anode.factory.FakeWorker;
import com.anode.factory.IntegrationTest;
import com.anode.factory.TestRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class AgentRoleTest {

    @Autowired MockMvcTester mvc;
    final JsonMapper json = JsonMapper.builder().build();
    FakeWorker worker;

    @BeforeEach
    void setUp() {
        TestRepository.register(mvc);
        worker = new FakeWorker(mvc, "roles-worker-" + UUID.randomUUID().toString().substring(0, 8));
        while (!worker.hasNothingToClaim()) {
            // other tests' leftovers: claimed and abandoned
        }
    }

    JsonNode body(MvcTestResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    JsonNode get(String uri) {
        MvcTestResult result = mvc.get().uri(uri).with(developer()).exchange();
        assertThat(result).hasStatusOk();
        return body(result);
    }

    MvcTestResult post(String uri, Object body) {
        return mvc.post().uri(uri).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)).exchange();
    }

    UUID create(String workflow) {
        var result = mvc.post().uri("/api/work-orders").with(developer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(workflow == null
                        ? Map.of("repository", TestRepository.KEY, "title", "Speed", "description", "Faster totals")
                        : Map.of("repository", TestRepository.KEY, "title", "Speed", "description", "Faster totals",
                                "workflow", workflow)))
                .exchange();
        assertThat(result).hasStatus(201);
        return UUID.fromString(body(result).get("id").asString());
    }

    String currentStep(UUID id) {
        return get("/api/work-orders/" + id).get("workOrder").path("currentStep").asString();
    }

    @Test
    void agentRunsReceiveThePromptPinnedWhenTheirWorkOrderWasCreated() {
        JsonNode analyst = get("/api/agent-roles/analyst");
        assertThat(analyst.get("builtIn").asBoolean()).isTrue();
        int before = analyst.get("latestVersion").asInt();

        UUID pinned = create(null);
        FakeWorker.Job classify = worker.claim();
        assertThat(classify.input().get("prompt").get("text").asString()).contains("<untrusted_input>");

        MvcTestResult published = post("/api/agent-roles/analyst/prompts",
                Map.of("text", "You analyse.\nBe brief.", "note", "shorter"));
        assertThat(published).hasStatus(201);
        assertThat(body(published).get("version").asInt()).isEqualTo(before + 1);

        worker.complete(classify, classification("BUG", "LOW"));
        await().until(() -> "analyze".equals(currentStep(pinned)));
        assertThat(worker.claim().input().get("prompt").get("version").asInt()).isEqualTo(before);

        UUID later = create(null);
        worker.complete(worker.claim(), classification("BUG", "LOW"));
        await().until(() -> "analyze".equals(currentStep(later)));
        JsonNode prompt = worker.claim().input().get("prompt");
        assertThat(prompt.get("version").asInt()).isEqualTo(before + 1);
        assertThat(prompt.get("text").asString()).isEqualTo("You analyse.\nBe brief.");
    }

    @Test
    void promptVersionsAreImmutableAndDiffedAgainstThePreviousOne() {
        post("/api/agent-roles/specifier/prompts", Map.of("text", "Write tests.\nCover every criterion.", "note", "a"));
        MvcTestResult second = post("/api/agent-roles/specifier/prompts",
                Map.of("text", "Write tests.\nCover every criterion through the API.", "note", "b"));
        int version = body(second).get("version").asInt();

        JsonNode fetched = get("/api/agent-roles/specifier/prompts/" + version);
        assertThat(fetched.get("diff")).extracting(JsonNode::asString).containsExactly(
                "  Write tests.", "- Cover every criterion.", "+ Cover every criterion through the API.");
        assertThat(get("/api/agent-roles/specifier/prompts").get(0).get("version").asInt()).isEqualTo(version);

        assertThat(post("/api/agent-roles/specifier/prompts", Map.of("text", "x", "note", " "))).hasStatus(400);
        assertThat(mvc.post().uri("/api/agent-roles/specifier/prompts").with(developer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"x\",\"note\":\"n\"}").exchange())
                .hasStatus(403);
        assertThat(mvc.put().uri("/api/agent-roles/specifier/prompts/1").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange()).hasStatus(405);
        assertThat(mvc.put().uri("/api/settings").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"document\":{\"roles\":{\"specifier\":{\"promptVersion\":999}}},\"reason\":\"r\"}")
                .exchange()).hasStatus(422);
    }

    @Test
    void aCustomReviewRoleVotesWithTheReviewers() {
        assertThat(post("/api/agent-roles", Map.of("name", "perf-reviewer", "description", "Reviews performance",
                "prompt", "Review the diff for performance."))).hasStatus(201);
        assertThat(post("/api/agent-roles", Map.of("name", "perf-reviewer", "description", "d", "prompt", "p")))
                .hasStatus(409);
        assertThat(get("/api/agent-roles/perf-reviewer").get("outputSchema").asString()).isEqualTo("reviewer");

        assertThat(post("/api/workflows/perf-review/versions", Map.of("note", "test", "definition", json.readTree("""
                { "start": "review", "steps": [
                  { "id": "review", "kind": "agent", "roles": ["reviewer", "perf-reviewer"], "join": "all",
                    "next": [{ "when": { "fact": "verdict", "equals": "PASS" }, "to": "@done" }, { "to": "@blocked" }] }
                ] }""")))).hasStatus(201);

        UUID id = create("perf-review");
        for (int i = 0; i < 2; i++) {
            FakeWorker.Job job = worker.claim();
            if (job.task().equals("perf-reviewer")) {
                assertThat(job.input().get("prompt").get("text").asString()).isEqualTo("Review the diff for performance.");
                worker.complete(job, review("REVISE"));
            } else {
                worker.complete(job, review("PASS"));
            }
        }
        await().until(() -> "BLOCKED".equals(get("/api/work-orders/" + id).get("workOrder").get("status").asString()));
        assertThat(get("/api/work-orders/" + id).get("workOrder").get("facts").get("verdict").asString())
                .isEqualTo("REVISE");
    }
}
