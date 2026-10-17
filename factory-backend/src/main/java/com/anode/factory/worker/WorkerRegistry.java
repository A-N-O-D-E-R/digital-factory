package com.anode.factory.worker;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Registers workers and issues their credential: a JWT signed by the factory (subject = worker
 * id, group factory-workers). A revoked worker's token stops working at once (see {@link
 * ActiveWorkerTokenValidator}).
 */
@Service
public class WorkerRegistry {

    /** The group mapped to ROLE_WORKER (anode.security.roles.mapping). */
    static final String WORKERS_GROUP = "factory-workers";

    private final WorkerRepository workers;
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final Duration validity;

    WorkerRegistry(WorkerRepository workers, JwtEncoder encoder, Clock clock,
                   @Value("${anode.security.jwt.issuer}") String issuer,
                   @Value("${factory.workers.token-validity:365d}") Duration validity) {
        this.workers = workers;
        this.encoder = encoder;
        this.clock = clock;
        this.issuer = issuer;
        this.validity = validity;
    }

    /** A registered worker and its token, shown once. */
    public record Registration(Worker worker, String token) {}

    @Transactional
    public Registration register(String name, String admin) {
        if (workers.existsByName(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A worker named '" + name + "' exists");
        }
        Worker worker = workers.save(new Worker(name, admin, clock.instant()));
        var claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(worker.id().toString())
                .claim("name", name)
                .claim("groups", List.of(WORKERS_GROUP))
                .issuedAt(clock.instant())
                .expiresAt(clock.instant().plus(validity))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new Registration(worker, token);
    }

    @Transactional
    public void revoke(UUID id) {
        workers.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)).revoke();
    }

    public List<Worker> all() {
        return workers.findAllByOrderByName();
    }

    boolean isActive(UUID id) {
        return workers.existsByIdAndActiveTrue(id);
    }
}
