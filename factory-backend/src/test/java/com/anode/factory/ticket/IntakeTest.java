package com.anode.factory.ticket;

import static com.anode.factory.Users.admin;
import static com.anode.factory.Users.developer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

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
class IntakeTest {

    @Autowired MockMvcTester mvc;
    @Autowired FakeTicketSource issues;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        TestRepository.register(mvc);
    }

    JsonNode body(MvcTestResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    MvcTestResult importTicket(Object request) {
        return mvc.post().uri("/api/tickets/import").with(developer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)).exchange();
    }

    JsonNode workOrder(String id) {
        MvcTestResult result = mvc.get().uri("/api/work-orders/{id}", id).with(developer()).exchange();
        assertThat(result).hasStatusOk();
        return body(result).get("workOrder");
    }

    UUID rule(String name, String filter, int maxRead, int maxCreated) {
        MvcTestResult created = mvc.post().uri("/api/intake-rules").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name, "source", "github", "project", "acme/api",
                        "filter", filter, "repository", TestRepository.KEY, "maxRead", maxRead,
                        "maxCreated", maxCreated)))
                .exchange();
        assertThat(created).hasStatus(201);
        return UUID.fromString(body(created).get("id").asString());
    }

    JsonNode poll(UUID rule) {
        MvcTestResult result = mvc.post().uri("/api/intake-rules/{id}/poll", rule).with(admin()).with(csrf()).exchange();
        assertThat(result).hasStatusOk();
        return body(result);
    }

    @Test
    void aTicketBecomesAWorkOrderOnceUnlessReimported() {
        Ticket ticket = issues.file("acme/api", 101, "Fix rounding", "bug");

        MvcTestResult imported = importTicket(Map.of("ticket", ticket.url()));
        assertThat(imported).hasStatus(201);
        String first = body(imported).get("workOrderId").asString();
        JsonNode workOrder = workOrder(first);
        assertThat(workOrder.get("repository").asString()).isEqualTo(TestRepository.KEY);
        assertThat(workOrder.get("title").asString()).isEqualTo("Fix rounding");
        assertThat(workOrder.get("description").asString()).contains("Body of Fix rounding")
                .endsWith("Ticket: " + ticket.url());

        assertThat(importTicket(Map.of("ticket", "github:acme/api#101"))).hasStatus(409);
        MvcTestResult again = importTicket(Map.of("ticket", "github:acme/api#101", "reimport", true));
        assertThat(again).hasStatus(201);
        assertThat(body(again).get("workOrderId").asString()).isNotEqualTo(first);

        assertThat(importTicket(Map.of("ticket", "https://example.test/nothing"))).hasStatus(422);
        assertThat(mvc.post().uri("/api/tickets/import").with(com.anode.factory.Users.viewer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"ticket\":\"github:acme/api#101\"}").exchange())
                .hasStatus(403);
    }

    @Test
    void rulesImportNewTicketsWithinTheirCaps() {
        for (int number = 201; number <= 205; number++) {
            issues.file("acme/api", number, "Docs " + number, "factory-docs");
        }
        issues.file("acme/api", 206, "Not for the factory", "question");
        UUID rule = rule("docs", "factory-docs", 4, 2);

        assertThat(poll(rule)).isEqualTo(json.readTree("{\"read\":4,\"created\":2}"));
        assertThat(poll(rule)).isEqualTo(json.readTree("{\"read\":4,\"created\":2}"));
        assertThat(poll(rule)).isEqualTo(json.readTree("{\"read\":4,\"created\":0}")); // #201 is beyond maxRead

        MvcTestResult tickets = mvc.get().uri("/api/tickets").with(developer()).exchange();
        assertThat(body(tickets).toString()).contains("github:acme/api#202", "github:acme/api#205")
                .doesNotContain("github:acme/api#201", "github:acme/api#206");

        assertThat(mvc.post().uri("/api/intake-rules").with(developer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "dev", "source", "github", "project", "acme/api",
                        "filter", "x", "repository", TestRepository.KEY)))
                .exchange()).hasStatus(403);
        assertThat(mvc.post().uri("/api/intake-rules").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "jira", "source", "jira", "project", "OPS",
                        "filter", "x", "repository", TestRepository.KEY)))
                .exchange()).hasStatus(422);
    }

    @Test
    void aFailingRuleRecordsItsError() {
        UUID rule = rule("broken", "broken", 10, 1);
        assertThat(poll(rule).get("created").asInt()).isZero();
        JsonNode rules = body(mvc.get().uri("/api/intake-rules").with(admin()).exchange());
        JsonNode broken = null;
        for (JsonNode r : rules) {
            if (r.get("name").asString().equals("broken")) {
                broken = r;
            }
        }
        assertThat(broken.get("lastError").asString()).isEqualTo("GitHub is down");
        assertThat(broken.get("lastPolledAt").isNull()).isFalse();
    }

    @Test
    void progressIsCommentedOnTheTicketOnceEach() {
        issues.file("acme/api", 301, "Flaky test", "bug");
        String id = body(importTicket(Map.of("ticket", "github:acme/api#301"))).get("workOrderId").asString();
        await().until(() -> issues.comments().stream().anyMatch(c -> c.startsWith("github:acme/api#301: The factory started")));

        assertThat(mvc.post().uri("/api/work-orders/{id}/cancel", id).with(developer()).with(csrf()).exchange())
                .hasStatus(204);
        await().until(() -> issues.comments().stream().anyMatch(c -> c.equals(
                "github:acme/api#301: Work order " + id + " finished: CANCELLED — cancelled by dev")));
        assertThat(issues.comments().stream().filter(c -> c.startsWith("github:acme/api#301")).count()).isEqualTo(2);
        // moved when started; not to done, the work order was cancelled
        assertThat(issues.transitions()).filteredOn(t -> t.startsWith("github:acme/api#301"))
                .containsExactly("github:acme/api#301: In Progress");
    }
}
