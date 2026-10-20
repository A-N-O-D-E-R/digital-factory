package com.anode.factory.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.anode.factory.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The frontend's files and routes, served to anyone (src/test/resources/static stands in for the build). */
@IntegrationTest
class FrontendPagesTest {

    @Autowired MockMvcTester mvc;

    @Test
    void theAppsRoutesGetItsPageAndItsFilesAreServed() {
        assertThat(mvc.get().uri("/").exchange()).hasStatusOk().hasForwardedUrl("index.html"); // the welcome page
        assertThat(mvc.get().uri("/work-orders/123").exchange()).hasStatusOk().bodyText().contains("<div id=\"root\">");
        assertThat(mvc.get().uri("/assets/app.js").exchange()).hasStatusOk().bodyText().contains("console.log");
        assertThat(mvc.get().uri("/assets/missing.js").exchange()).hasStatus(404);
    }

    @Test
    void theBackendsPathsAreNotPages() {
        assertThat(mvc.get().uri("/api/work-orders").exchange()).as("still signed in only").hasStatus(401);
        assertThat(mvc.post().uri("/work-orders/123").exchange().getResponse().getStatus()).isNotEqualTo(200);
    }
}
