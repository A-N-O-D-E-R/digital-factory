package com.anode.factory.prompt;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/agent-roles")
class AgentRoleController {

    record RoleView(String name, String description, boolean writesCode, String outputSchema, boolean builtIn,
                    Integer latestVersion, String createdBy, Instant createdAt) {}

    record VersionView(String role, int version, String text, String note, String createdBy, Instant createdAt,
                       List<String> diff) {}

    record CreateRequest(@NotBlank @Size(max = 63) String name, @NotBlank @Size(max = 500) String description,
                         @NotBlank @Size(max = 100_000) String prompt) {}

    record PublishRequest(@NotBlank @Size(max = 100_000) String text, @NotBlank @Size(max = 200) String note) {}

    private final AgentRoles roles;

    AgentRoleController(AgentRoles roles) {
        this.roles = roles;
    }

    private RoleView view(AgentRole r) {
        return new RoleView(r.name(), r.description(), r.writesCode(), r.outputSchema(), r.builtIn(),
                roles.latest(r.name()).map(PromptVersion::version).orElse(null), r.createdBy(), r.createdAt());
    }

    private VersionView view(PromptVersion p, boolean withDiff) {
        return new VersionView(p.role(), p.version(), p.text(), p.note(), p.createdBy(), p.createdAt(),
                withDiff ? roles.diff(p) : null);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    List<RoleView> all() {
        return roles.all().stream().map(this::view).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<RoleView> create(@Valid @RequestBody CreateRequest request, Authentication admin) {
        AgentRole role = roles.create(request.name(), request.description(), request.prompt(), admin.getName());
        return ResponseEntity.created(URI.create("/api/agent-roles/" + role.name())).body(view(role));
    }

    @GetMapping("/{role}")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    RoleView role(@PathVariable String role) {
        return view(roles.get(role));
    }

    @GetMapping("/{role}/prompts")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    List<VersionView> history(@PathVariable String role) {
        return roles.history(role).stream().map(p -> view(p, false)).toList();
    }

    @PostMapping("/{role}/prompts")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<VersionView> publish(@PathVariable String role, @Valid @RequestBody PublishRequest request,
                                        Authentication admin) {
        PromptVersion published = roles.publish(role, request.text(), request.note(), admin.getName());
        return ResponseEntity.created(URI.create("/api/agent-roles/" + role + "/prompts/" + published.version()))
                .body(view(published, true));
    }

    /** With its line diff against the previous version. */
    @GetMapping("/{role}/prompts/{version}")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    VersionView version(@PathVariable String role, @PathVariable int version) {
        return roles.prompt(role, version).map(p -> view(p, true))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No prompt " + role + " v" + version));
    }
}
