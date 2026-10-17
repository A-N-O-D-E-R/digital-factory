package com.anode.factory.workflow;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/workflows")
class WorkflowController {

    record VersionView(String name, int version, JsonNode definition, String note, String createdBy, Instant createdAt) {}

    record PublishRequest(JsonNode definition, String note) {}

    record ValidationView(boolean valid, List<String> errors) {}

    private final WorkflowCatalog catalog;
    private final JsonMapper json;

    WorkflowController(WorkflowCatalog catalog, JsonMapper json) {
        this.catalog = catalog;
        this.json = json;
    }

    private VersionView view(WorkflowVersion v) {
        return new VersionView(v.name(), v.version(), json.readTree(v.definition()), v.note(), v.createdBy(), v.createdAt());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    List<VersionView> latestOfEach() {
        return catalog.latestOfEach().stream().map(this::view).toList();
    }

    @GetMapping("/{name}")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    VersionView latest(@PathVariable String name) {
        return catalog.latest(name).map(this::view).orElseThrow(() -> notFound(name));
    }

    @GetMapping("/{name}/versions")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    List<VersionView> history(@PathVariable String name) {
        return catalog.history(name).stream().map(this::view).toList();
    }

    @GetMapping("/{name}/versions/{version}")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    VersionView version(@PathVariable String name, @PathVariable int version) {
        return catalog.version(name, version).map(this::view).orElseThrow(() -> notFound(name + " v" + version));
    }

    @PostMapping("/{name}/versions")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<VersionView> publish(@PathVariable String name, @RequestBody PublishRequest request, Authentication author) {
        if (!name.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid workflow name");
        }
        if (request.note() == null || request.note().isBlank()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "A note is required");
        }
        WorkflowVersion published = catalog.publish(name, json.writeValueAsString(request.definition()),
                request.note(), author.getName());
        return ResponseEntity.created(URI.create("/api/workflows/" + name + "/versions/" + published.version()))
                .body(view(published));
    }

    @PostMapping("/validate")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    ValidationView validate(@RequestBody JsonNode definition) {
        var result = catalog.validate(json.writeValueAsString(definition));
        return new ValidationView(result.valid(), result.errors());
    }

    private static ResponseStatusException notFound(String what) {
        return new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "No workflow " + what);
    }
}
