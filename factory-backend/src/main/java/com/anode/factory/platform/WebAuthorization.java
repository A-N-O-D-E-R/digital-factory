package com.anode.factory.platform;

import com.anode.security.AuthorizationRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Who may call which part of the API, checked before anything else: people use /api, workers use
 * /internal and nothing else. Finer rules are on the endpoints. The frontend's pages are anyone's:
 * what they show comes from /api.
 */
@Configuration(proxyBeanMethods = false)
class WebAuthorization {

    @Bean
    AuthorizationRules factoryAreas() {
        return rules -> rules
                .requestMatchers("/internal/**").hasRole("WORKER")
                .requestMatchers("/api/**").hasAnyRole("ADMIN", "DEVELOPER", "VIEWER")
                .requestMatchers(FrontendPages::isPage).permitAll();
    }

    /** The API answers 401 to a missing session (the app sends the user to log in), not a redirect to a page. */
    @Bean
    Customizer<HttpSecurity> apiUnauthorized() {
        return http -> {
            try {
                http.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }
}
