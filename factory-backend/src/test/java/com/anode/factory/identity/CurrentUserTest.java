package com.anode.factory.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import com.anode.factory.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class CurrentUserTest {

    @Autowired MockMvcTester mvc;

    @Test
    void reportsTheRolesAndPermissionsOfTheSignedInUser() {
        var response = mvc.get().uri("/api/user")
                .with(oidcLogin().idToken(token -> token.subject("dev-1"))
                        .authorities(new SimpleGrantedAuthority("ROLE_DEVELOPER")));

        assertThat(response).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.name", name -> assertThat(name).asString().isEqualTo("dev-1"))
                .hasPathSatisfying("$.roles", roles -> assertThat(roles).asArray().containsExactly("DEVELOPER"))
                .hasPathSatisfying("$.permissions", p -> assertThat(p).asArray()
                        .contains("work-order:write", "approval:decide").doesNotContain("settings:write"));
    }

    @Test
    void anonymousCallsAreRejected() {
        assertThat(mvc.get().uri("/api/user")).hasStatus(401);
    }
}
