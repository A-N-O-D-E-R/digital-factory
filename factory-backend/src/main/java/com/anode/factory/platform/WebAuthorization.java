package com.anode.factory.platform;

import com.anode.security.AuthorizationRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Who may call which part of the API, checked before anything else: people use /api, workers use
 * /internal and nothing else. Finer rules are on the endpoints.
 */
@Configuration(proxyBeanMethods = false)
class WebAuthorization {

    @Bean
    AuthorizationRules factoryAreas() {
        return rules -> rules
                .requestMatchers("/internal/**").hasRole("WORKER")
                .requestMatchers("/api/**").hasAnyRole("ADMIN", "DEVELOPER", "VIEWER");
    }
}
