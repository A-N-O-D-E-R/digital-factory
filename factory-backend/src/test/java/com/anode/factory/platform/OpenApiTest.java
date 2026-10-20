package com.anode.factory.platform;

import static com.anode.factory.Users.viewer;
import static org.assertj.core.api.Assertions.assertThat;

import com.anode.factory.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The API's OpenAPI description, written to target/openapi.json: the frontend's types are generated from it. */
@IntegrationTest
class OpenApiTest {

    @Autowired MockMvcTester mvc;

    @Test
    void theApiIsDescribedForTheFrontend() throws Exception {
        var result = mvc.get().uri("/v3/api-docs").with(viewer()).exchange();
        assertThat(result).hasStatusOk();
        String description = result.getResponse().getContentAsString();
        assertThat(description).contains("/api/work-orders/{id}", "/api/user");
        Files.writeString(Path.of("target", "openapi.json"), description);
    }
}
