package com.anode.factory.platform;

import static com.anode.factory.Users.admin;
import static com.anode.factory.Users.developer;
import static org.assertj.core.api.Assertions.assertThat;

import com.anode.factory.IntegrationTest;
import com.anode.factory.ticket.FakeTicketSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class IntegrationsTest {

    @Autowired MockMvcTester mvc;
    @Autowired FakeTicketSource github;
    final JsonMapper json = JsonMapper.builder().build();

    JsonNode integrations(String query) {
        var result = mvc.get().uri("/api/integrations" + query).with(admin()).exchange();
        assertThat(result).hasStatusOk();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    static JsonNode named(JsonNode integrations, String name) {
        for (JsonNode integration : integrations) {
            if (integration.get("name").asString().equals(name)) {
                return integration;
            }
        }
        throw new AssertionError("no " + name);
    }

    @Test
    void eachIntegrationIsConnectedFailingOrNotConfigured() {
        try {
            JsonNode all = integrations("?refresh=true");
            assertThat(named(all, "github").get("ok").asBoolean()).isTrue();
            assertThat(named(all, "github").get("detail").asString()).isEqualTo("Fake GitHub");
            assertThat(named(all, "jira").get("configured").asBoolean()).isFalse();

            github.outage("401 Bad credentials");
            assertThat(named(integrations(""), "github").get("ok").asBoolean()).as("checked a minute ago").isTrue();
            JsonNode failing = named(integrations("?refresh=true"), "github");
            assertThat(failing.get("ok").asBoolean()).isFalse();
            assertThat(failing.get("error").asString()).isEqualTo("401 Bad credentials");

            assertThat(mvc.get().uri("/api/integrations").with(developer()).exchange()).hasStatus(403);
        } finally {
            github.outage(null);
        }
    }
}
