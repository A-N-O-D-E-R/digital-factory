package com.anode.factory.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.anode.factory.FakeWorker;
import com.anode.factory.IntegrationTest;
import com.anode.factory.Users;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The mock login (dev/run-local.sh, the compose try-out) still lets workers in with their own token. */
@IntegrationTest
@TestPropertySource(properties = "anode.security.mock=true")
class MockLoginWorkerTokensTest {

    @Autowired MockMvcTester mvc;

    @Test
    void workersSignInWithTheirTokenAndARevokedOneIsLockedOut() {
        FakeWorker worker = new FakeWorker(mvc, "mock-mode-worker");
        assertThat(mvc.post().uri("/internal/runs/claim").header("Authorization", worker.bearer()).exchange()
                .getResponse().getStatus()).isIn(200, 204);

        assertThat(mvc.delete().uri("/api/workers/{id}", worker.id()).with(Users.admin())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .exchange()).hasStatus(204);
        assertThat(mvc.post().uri("/internal/runs/claim").header("Authorization", worker.bearer())).hasStatus(401);
    }
}
