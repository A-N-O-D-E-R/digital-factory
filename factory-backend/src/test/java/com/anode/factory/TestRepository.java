package com.anode.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The repository work orders of the tests are on, registered once (on the fake GitHub). */
public final class TestRepository {

    public static final String KEY = "github:acme/api";

    private TestRepository() {}

    public static void register(MockMvcTester mvc) {
        int status = mvc.post().uri("/api/repositories").with(Users.admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"forge":"GITHUB","path":"acme/api","settings":{"defaultBranch":"main",
                         "testCommand":"mvn -B verify","testReports":"**/target/surefire-reports/*.xml",
                         "sandboxImage":"factory-sandbox:jdk25"}}""")
                .exchange().getResponse().getStatus();
        assertThat(status).isIn(201, 409);
    }
}
