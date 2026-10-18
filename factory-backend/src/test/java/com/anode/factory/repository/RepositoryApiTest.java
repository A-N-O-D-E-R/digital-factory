package com.anode.factory.repository;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
class RepositoryApiTest {

    static final String SETTINGS = """
            {"defaultBranch":"main","testCommand":"pnpm test","testReports":"reports/*.xml","sandboxImage":"sandbox:node"}""";

    @Autowired MockMvcTester mvc;

    int register(RequestPostProcessor user, String path) {
        return mvc.post().uri("/api/repositories").with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"forge\":\"GITLAB\",\"path\":\"" + path + "\",\"settings\":" + SETTINGS + "}")
                .exchange().getResponse().getStatus();
    }

    @Test
    void adminsRegisterAndDisableRepositoriesEveryoneSeesThem() {
        assertThat(register(admin(), "tools/web/app")).isEqualTo(201);
        assertThat(register(admin(), "tools/web/app")).isEqualTo(409);

        assertThat(mvc.put().uri("/api/repositories").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"gitlab:tools/web/app\",\"settings\":" + SETTINGS + ",\"active\":false}"))
                .hasStatusOk().bodyJson().hasPathSatisfying("$.active", a -> assertThat(a).isEqualTo(false));

        assertThat(mvc.get().uri("/api/repositories").with(viewer())).hasStatusOk().bodyJson()
                .hasPathSatisfying("$[?(@.key == 'gitlab:tools/web/app')].testCommand",
                        c -> assertThat(c).asArray().containsExactly("pnpm test"));

        assertThat(mvc.post().uri("/api/work-orders").with(developer()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"repository\":\"gitlab:tools/web/app\",\"title\":\"t\",\"description\":\"d\"}"))
                .hasStatus(422);
    }

    @Test
    void onlyAdminsRegisterValidPaths() {
        assertThat(register(developer(), "tools/other")).isEqualTo(403);
        assertThat(register(admin(), "no-owner")).isEqualTo(400);
        assertThat(register(admin(), "../etc/passwd")).isEqualTo(400);
    }
}
