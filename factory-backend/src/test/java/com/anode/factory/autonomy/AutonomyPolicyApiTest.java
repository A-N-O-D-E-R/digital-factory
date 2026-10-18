package com.anode.factory.autonomy;

import static com.anode.factory.Users.admin;
import static com.anode.factory.Users.developer;
import static com.anode.factory.Users.viewer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.anode.factory.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
class AutonomyPolicyApiTest {

    @Autowired MockMvcTester mvc;

    MvcTestResult grant(RequestPostProcessor user, String type, String body) {
        return mvc.put().uri("/api/autonomy-policy/{type}", type).with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    @Test
    void everyClassStopsAtTheDraftPrUntilAnAdminGrantsMore() {
        assertThat(mvc.get().uri("/api/autonomy-policy").with(viewer())).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.length()", n -> assertThat(n).isEqualTo(8))
                .hasPathSatisfying("$[?(@.type == 'SECURITY')].level", l -> assertThat(l).asArray().containsExactly("DRAFT_PR"));

        assertThat(grant(admin(), "DOCUMENTATION", "{\"level\":\"MERGE\",\"minSuccessRate\":0.9}")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.updatedBy", by -> assertThat(by).isEqualTo("ada"))
                .hasPathSatisfying("$.successRate", rate -> assertThat(rate).isNull());
    }

    @Test
    void onlyAdminsGrantAndWithinBounds() {
        assertThat(grant(developer(), "TEST", "{\"level\":\"MERGE\",\"minSuccessRate\":0.9}")).hasStatus(403);
        assertThat(grant(admin(), "TEST", "{\"level\":\"MERGE\",\"minSuccessRate\":1.5}")).hasStatus(400);
        assertThat(grant(admin(), "NOPE", "{\"level\":\"MERGE\",\"minSuccessRate\":0.5}")).hasStatus(400);
    }
}
