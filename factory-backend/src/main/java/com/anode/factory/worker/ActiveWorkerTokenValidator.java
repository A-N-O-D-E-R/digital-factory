package com.anode.factory.worker;

import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Self-issued tokens of the workers group are only valid while their worker is active. */
@Component
class ActiveWorkerTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final WorkerRegistry registry;

    ActiveWorkerTokenValidator(WorkerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> groups = token.getClaimAsStringList("groups");
        if (groups == null || !groups.contains(WorkerRegistry.WORKERS_GROUP)) {
            return OAuth2TokenValidatorResult.success();
        }
        UUID id;
        try {
            id = UUID.fromString(token.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            return revoked();
        }
        return registry.isActive(id) ? OAuth2TokenValidatorResult.success() : revoked();
    }

    private static OAuth2TokenValidatorResult revoked() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Unknown or revoked worker", null));
    }
}
