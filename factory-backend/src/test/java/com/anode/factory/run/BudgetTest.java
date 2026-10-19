package com.anode.factory.run;

import static com.anode.factory.FakeWorker.Outputs.classification;
import static com.anode.factory.Users.admin;
import static com.anode.factory.Users.developer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.anode.factory.FakeWorker;
import com.anode.factory.IntegrationTest;
import com.anode.factory.TestRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Agent runs reserve their role's budget (built-in: classifier 0.20, analyst 2.00 USD); the fake worker reports 0.01. */
@IntegrationTest
class BudgetTest {

    @Autowired MockMvcTester mvc;
    final JsonMapper json = JsonMapper.builder().build();
    FakeWorker worker;

    @BeforeEach
    void setUp() {
        TestRepository.register(mvc);
        worker = new FakeWorker(mvc, "budget-worker-" + UUID.randomUUID().toString().substring(0, 8));
        while (!worker.hasNothingToClaim()) {
            // other tests' leftovers: claimed and abandoned
        }
    }

    @AfterEach
    void noCapsNoPause() {
        budgets("{}");
        assertThat(mvc.post().uri("/api/factory/resume").with(admin()).with(csrf()).exchange()).hasStatusOk();
    }

    JsonNode budgets(String body) {
        var result = mvc.put().uri("/api/budgets").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange();
        assertThat(result).hasStatusOk();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    BigDecimal spentToday() {
        var result = mvc.get().uri("/api/budgets").with(developer()).exchange();
        assertThat(result).hasStatusOk();
        return json.readTree(result.getResponse().getContentAsByteArray()).get("spentTodayUsd").decimalValue();
    }

    UUID create() {
        var result = mvc.post().uri("/api/work-orders").with(developer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"repository":"%s","title":"Bump","description":"Bump the parent"}""".formatted(TestRepository.KEY))
                .exchange();
        assertThat(result).hasStatus(201);
        return UUID.fromString(json.readTree(result.getResponse().getContentAsByteArray()).get("id").asString());
    }

    JsonNode workOrder(UUID id) {
        var result = mvc.get().uri("/api/work-orders/{id}", id).with(developer()).exchange();
        assertThat(result).hasStatusOk();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    void aWorkOrderPausesAtItsBudgetAndContinuesWhenRaised() {
        budgets("{\"workOrderUsd\":2.005}");
        UUID id = create();

        FakeWorker.Job classify = worker.claim(); // reserves 0.20 of 2.005
        assertThat(classify.task()).isEqualTo("classifier");
        worker.complete(classify, classification("BUG", "LOW")); // costs 0.01: the analyst's 0.01 + 2.00 no longer fits

        org.awaitility.Awaitility.await().until(() -> "PAUSED".equals(workOrder(id).get("workOrder").get("status").asString()));
        JsonNode paused = workOrder(id);
        assertThat(paused.get("workOrder").get("pauseReason").asString()).contains("work-order budget");
        assertThat(paused.get("costUsd").decimalValue()).isEqualByComparingTo("0.01");
        assertThat(worker.hasNothingToClaim()).isTrue();

        budgets("{\"workOrderUsd\":10}");
        assertThat(workOrder(id).get("workOrder").get("status").asString()).isEqualTo("RUNNING");
        assertThat(worker.claim().task()).isEqualTo("analyst");
    }

    @Test
    void parallelRunsCannotOvershootTheDailyBudgetTogether() {
        UUID first = create();
        UUID second = create();
        budgets("{\"dailyUsd\":" + spentToday().add(new BigDecimal("0.205")) + "}");

        FakeWorker.Job job = worker.claim(); // reserves 0.20: the other classifier no longer fits
        assertThat(job.input().get("workOrder").get("id").asString()).isEqualTo(first.toString());
        assertThat(worker.hasNothingToClaim()).isTrue();
        assertThat(workOrder(second).get("workOrder").get("pauseReason").asString()).contains("daily budget");

        budgets("{}");
        assertThat(worker.claim().input().get("workOrder").get("id").asString()).isEqualTo(second.toString());
    }

    @Test
    void nothingIsDispatchedWhileTheFactoryIsPaused() {
        assertThat(mvc.post().uri("/api/factory/pause").with(developer()).with(csrf()).exchange()).hasStatus(403);
        assertThat(mvc.put().uri("/api/budgets").with(developer()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{}").exchange()).hasStatus(403);
        assertThat(mvc.put().uri("/api/budgets").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dailyUsd\":-1}").exchange()).hasStatus(400);

        assertThat(mvc.post().uri("/api/factory/pause").with(admin()).with(csrf()).exchange()).hasStatusOk();
        UUID id = create();
        assertThat(worker.hasNothingToClaim()).isTrue();
        JsonNode paused = workOrder(id).get("workOrder");
        assertThat(paused.get("status").asString()).isEqualTo("PAUSED");
        assertThat(paused.get("pauseReason").asString()).isEqualTo("factory paused");

        assertThat(mvc.post().uri("/api/factory/resume").with(admin()).with(csrf()).exchange()).hasStatusOk();
        FakeWorker.Job job = worker.claim();
        assertThat(job.input().get("workOrder").get("id").asString()).isEqualTo(id.toString());
    }
}
