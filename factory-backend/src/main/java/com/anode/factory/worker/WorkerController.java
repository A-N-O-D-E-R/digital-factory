package com.anode.factory.worker;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workers")
@PreAuthorize("hasRole('ADMIN')")
class WorkerController {

    record WorkerView(UUID id, String name, boolean active, boolean draining, String version, String image,
                      Integer slots, Integer busy, Instant lastHeartbeatAt, Instant createdAt, String createdBy) {
        static WorkerView of(Worker w) {
            return new WorkerView(w.id(), w.name(), w.active(), w.draining(), w.version(), w.image(), w.slots(), w.busy(),
                    w.lastHeartbeatAt(), w.createdAt(), w.createdBy());
        }
    }

    record RegisterRequest(String name) {}

    /** The token is only ever returned here. */
    record Registered(WorkerView worker, String token) {}

    private final WorkerRegistry registry;

    WorkerController(WorkerRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    List<WorkerView> all() {
        return registry.all().stream().map(WorkerView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Registered register(@RequestBody RegisterRequest request, Authentication admin) {
        if (request.name() == null || !request.name().matches("[a-z0-9][a-z0-9.-]{0,62}")) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid worker name");
        }
        var registration = registry.register(request.name(), admin.getName());
        return new Registered(WorkerView.of(registration.worker()), registration.token());
    }

    /** A draining worker finishes its runs and claims no new one: for updating it without killing them. */
    @PostMapping("/{id}/drain")
    WorkerView drain(@PathVariable UUID id) {
        return WorkerView.of(registry.drain(id, true));
    }

    @PostMapping("/{id}/undrain")
    WorkerView undrain(@PathVariable UUID id) {
        return WorkerView.of(registry.drain(id, false));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID id) {
        registry.revoke(id);
    }
}
