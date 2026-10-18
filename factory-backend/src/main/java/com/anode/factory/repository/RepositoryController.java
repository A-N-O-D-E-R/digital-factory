package com.anode.factory.repository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories")
class RepositoryController {

    record SettingsRequest(@NotBlank String defaultBranch, @NotBlank String testCommand, @NotBlank String testReports,
                           @NotBlank String sandboxImage) {
        Repositories.Settings settings() {
            return new Repositories.Settings(defaultBranch, testCommand, testReports, sandboxImage);
        }
    }

    record RegisterRequest(@NotNull ForgeType forge, @NotBlank String path, @Valid @NotNull SettingsRequest settings) {}

    record UpdateRequest(@NotBlank String key, @Valid @NotNull SettingsRequest settings, boolean active) {}

    record RepositoryView(String key, ForgeType forge, String path, String defaultBranch, String testCommand,
                          String testReports, String sandboxImage, boolean active) {
        static RepositoryView of(CodeRepository r) {
            return new RepositoryView(r.key(), r.forge(), r.path(), r.defaultBranch(), r.testCommand(),
                    r.testReports(), r.sandboxImage(), r.active());
        }
    }

    private final Repositories repositories;

    RepositoryController(Repositories repositories) {
        this.repositories = repositories;
    }

    @GetMapping
    List<RepositoryView> all() {
        return repositories.all().stream().map(RepositoryView::of).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<RepositoryView> register(@Valid @RequestBody RegisterRequest request) {
        CodeRepository repository = repositories.register(request.forge(), request.path(), request.settings().settings());
        return ResponseEntity.created(URI.create("/api/repositories")).body(RepositoryView.of(repository));
    }

    /** Keys contain slashes (group/project), hence in the body rather than the path. */
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    RepositoryView update(@Valid @RequestBody UpdateRequest request) {
        return RepositoryView.of(repositories.update(request.key(), request.settings().settings(), request.active()));
    }
}
