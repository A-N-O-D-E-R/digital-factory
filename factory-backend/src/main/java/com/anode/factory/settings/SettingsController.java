package com.anode.factory.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The global layer without {@code ?repository=}, the repository's layer with it. */
@RestController
@RequestMapping("/api/settings")
class SettingsController {

    record UpdateRequest(JsonNode document, @Size(max = 2000) String reason) {}

    record RestoreRequest(@Size(max = 1000) String reason) {}

    record ChangeView(UUID id, String scope, JsonNode before, JsonNode after, String reason, String changedBy,
                      Instant changedAt) {}

    private final Settings settings;
    private final JsonMapper json;

    SettingsController(Settings settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    Settings.Layer layer(@RequestParam(required = false) String repository) {
        return settings.layer(repository);
    }

    @GetMapping("/defaults")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    JsonNode defaults() {
        return settings.defaults();
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    Settings.Layer update(@RequestParam(required = false) String repository, @Valid @RequestBody UpdateRequest request,
                          Authentication admin) {
        return settings.update(repository, request.document(), request.reason(), admin.getName());
    }

    @GetMapping("/changes")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    List<ChangeView> changes(@RequestParam(required = false) String repository) {
        return settings.changes(repository).stream()
                .map(c -> new ChangeView(c.id(), c.scope(), json.readTree(c.before()), json.readTree(c.after()),
                        c.reason(), c.changedBy(), c.changedAt()))
                .toList();
    }

    @PostMapping("/changes/{id}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    Settings.Layer restore(@PathVariable UUID id, @Valid @RequestBody(required = false) RestoreRequest request,
                           Authentication admin) {
        return settings.restore(id, request == null ? null : request.reason(), admin.getName());
    }
}
