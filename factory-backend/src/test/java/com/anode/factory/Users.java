package com.anode.factory;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Signed-in users of the tests, one per role. */
public final class Users {

    private Users() {}

    public static RequestPostProcessor admin() {
        return as("ada", "ROLE_ADMIN");
    }

    public static RequestPostProcessor developer() {
        return as("dev", "ROLE_DEVELOPER");
    }

    public static RequestPostProcessor viewer() {
        return as("vic", "ROLE_VIEWER");
    }

    private static RequestPostProcessor as(String name, String role) {
        return oidcLogin().idToken(token -> token.subject(name)).authorities(new SimpleGrantedAuthority(role));
    }
}
