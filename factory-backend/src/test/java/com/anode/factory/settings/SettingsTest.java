package com.anode.factory.settings;

import static com.anode.factory.FakeWorker.Outputs.classification;
import static com.anode.factory.Users.admin;
import static com.anode.factory.Users.developer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.anode.factory.FakeWorker;
import com.anode.factory.IntegrationTest;
import com.anode.factory.TestRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class SettingsTest {

    static final String REPO = "?repository=" + TestRepository.KEY;

    @Autowired MockMvcTester mvc;
    final JsonMapper json = JsonMapper.builder().build();
    FakeWorker worker;

    @BeforeEach
    void setUp() {
        TestRepository.register(mvc);
        worker = new FakeWorker(mvc, "settings-worker-" + UUID.randomUUID().toString().substring(0, 8));
        while (!worker.hasNothingToClaim()) {
            // other tests' leftovers: claimed and abandoned
        }
    }

    @AfterEach
    void backToDefaults() {
        assertThat(put(REPO, "{}", "reset")).hasStatusOk();
        assertThat(put("", "{}", "reset")).hasStatusOk();
    }

    MvcTestResult put(String query, String document, String reason) {
        return mvc.put().uri("/api/settings" + query).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("document", json.readTree(document), "reason", reason)))
                .exchange();
    }

    JsonNode body(MvcTestResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    JsonNode get(String uri) {
        MvcTestResult result = mvc.get().uri(uri).with(developer()).exchange();
        assertThat(result).hasStatusOk();
        return body(result);
    }

    UUID create() {
        return create(null);
    }

    UUID create(String workflow) {
        var result = mvc.post().uri("/api/work-orders").with(developer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"repository":"%s","title":"Docs","description":"Document the API"%s}""".formatted(TestRepository.KEY,
                        workflow == null ? "" : ",\"workflow\":\"" + workflow + "\""))
                .exchange();
        assertThat(result).hasStatus(201);
        return UUID.fromString(body(result).get("id").asString());
    }

    @Test
    void layersMergeOverTheDefaultsAndAreFrozenOnTheWorkOrder() {
        assertThat(put("", """
                {"roles":{"classifier":{"model":"sonnet"}}}""", "classify better")).hasStatusOk();
        assertThat(put(REPO, """
                {"roles":{"classifier":{"maxTurns":3}}}""", "small repo")).hasStatusOk();

        JsonNode classifier = get("/api/settings" + REPO).get("effective").get("roles").get("classifier");
        assertThat(classifier.get("model").asString()).isEqualTo("sonnet"); // global
        assertThat(classifier.get("maxTurns").asInt()).isEqualTo(3); // repository
        assertThat(classifier.get("effort").asString()).isEqualTo("low"); // defaults

        UUID id = create();
        FakeWorker.Job classify = worker.claim();
        JsonNode settings = classify.input().get("settings");
        assertThat(settings.get("modelId").asString()).isEqualTo("claude-sonnet-5-5");
        assertThat(settings.get("maxTurns").asInt()).isEqualTo(3);
        assertThat(settings.get("budgetUsd").decimalValue()).isEqualByComparingTo("0.20");

        // changed while the work order is in flight: it keeps what it was created with
        assertThat(put("", """
                {"roles":{"default":{"maxTurns":7}}}""", "shorter")).hasStatusOk();
        worker.complete(classify, classification("DOCUMENTATION", "LOW"));
        org.awaitility.Awaitility.await().until(() -> "analyze".equals(
                get("/api/work-orders/" + id).get("workOrder").path("currentStep").asString()));
        FakeWorker.Job analyze = worker.claim();
        assertThat(analyze.task()).isEqualTo("analyst");
        assertThat(analyze.input().get("settings").get("maxTurns").asInt()).isEqualTo(60);
        assertThat(analyze.input().get("settings").get("modelId").asString()).isEqualTo("claude-opus-5-5");
    }

    @Test
    void settingsThatWouldBeInvalidOnceMergedAreRefused() {
        JsonNode unknownModel = body(put("", """
                {"roles":{"reviewer":{"model":"gpt"}}}""", "try"));
        assertThat(unknownModel.get("errors").toString()).contains("unknown model 'gpt'");
        assertThat(put("", """
                {"roles":{"default":{"maxTurns":0}}}""", "try")).hasStatus(422);
        // null removes: the classifier would be left without its model
        assertThat(put("", """
                {"models":{"haiku":null}}""", "try")).hasStatus(422);
        assertThat(put("", "[]", "try")).hasStatus(400);
        assertThat(put("", "{}", " ")).hasStatus(400);

        // a global change may not break a repository built on it
        assertThat(put(REPO, """
                {"roles":{"reviewer":{"model":"sonnet"}}}""", "cheap reviews")).hasStatusOk();
        JsonNode broken = body(put("", """
                {"models":{"sonnet":null}}""", "no sonnet"));
        assertThat(broken.get("errors").toString()).contains("repository:" + TestRepository.KEY);

        assertThat(mvc.put().uri("/api/settings").with(developer()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"document\":{},\"reason\":\"r\"}").exchange()).hasStatus(403);
    }

    @Test
    void everyChangeIsRecordedAndCanBeRestored() {
        assertThat(put("", """
                {"roles":{"implementer":{"effort":"max"}}}""", "harder")).hasStatusOk();
        assertThat(put("", """
                {"roles":{"implementer":{"effort":"low"}}}""", "cheaper")).hasStatusOk();

        JsonNode changes = get("/api/settings/changes");
        JsonNode latest = changes.get(0);
        assertThat(latest.get("reason").asString()).isEqualTo("cheaper");
        assertThat(latest.get("changedBy").asString()).isEqualTo("ada");
        assertThat(latest.get("before").get("roles").get("implementer").get("effort").asString()).isEqualTo("max");
        assertThat(latest.get("after").get("roles").get("implementer").get("effort").asString()).isEqualTo("low");

        MvcTestResult restored = mvc.post().uri("/api/settings/changes/{id}/restore", latest.get("id").asString())
                .with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"too weak\"}").exchange();
        assertThat(restored).hasStatusOk();
        assertThat(body(restored).get("effective").get("roles").get("implementer").get("effort").asString())
                .isEqualTo("max");
        assertThat(get("/api/settings/changes").get(0).get("reason").asString())
                .startsWith("restore of " + latest.get("id").asString());
    }

    void publish(String name, String stepSettings) {
        assertThat(mvc.post().uri("/api/workflows/{name}/versions", name).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"note":"test","definition":{"start":"classify","steps":[
                          {"id":"classify","kind":"agent","roles":["classifier"],"settings":%s,"next":[{"to":"@done"}]}]}}"""
                        .formatted(stepSettings))
                .exchange()).hasStatus(201);
    }

    @Test
    void aStepOverridesItsRolesSettings() {
        publish("tuned", "{\"model\":\"sonnet\",\"maxTurns\":2}");
        create("tuned");
        JsonNode settings = worker.claim().input().get("settings");
        assertThat(settings.get("modelId").asString()).isEqualTo("claude-sonnet-5-5");
        assertThat(settings.get("maxTurns").asInt()).isEqualTo(2);
        assertThat(settings.get("effort").asString()).isEqualTo("low");

        publish("mistuned", "{\"model\":\"nope\"}");
        UUID id = create("mistuned");
        JsonNode failed = get("/api/work-orders/" + id).get("workOrder");
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("outcomeReason").asString()).contains("unknown model 'nope'");
        assertThat(worker.hasNothingToClaim()).isTrue();
    }
}
