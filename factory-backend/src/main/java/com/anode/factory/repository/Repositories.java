package com.anode.factory.repository;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The repositories the factory may work on. */
@Service
public class Repositories {

    private final CodeRepositoryRepository repositories;
    private final Clock clock;

    Repositories(CodeRepositoryRepository repositories, Clock clock) {
        this.repositories = repositories;
        this.clock = clock;
    }

    public record Settings(String defaultBranch, String testCommand, String testReports, String sandboxImage) {}

    @Transactional
    public CodeRepository register(ForgeType forge, String path, Settings settings) {
        // segments of name characters, none of them only dots (it ends up in clone and API URLs)
        if (!path.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+") || path.matches("(.*/)?\\.+(/.*)?")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid repository path: " + path);
        }
        if (repositories.existsById(CodeRepository.key(forge, path))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already registered: " + CodeRepository.key(forge, path));
        }
        return repositories.save(new CodeRepository(forge, path, settings.defaultBranch(), settings.testCommand(),
                settings.testReports(), settings.sandboxImage(), clock.instant()));
    }

    @Transactional
    public CodeRepository update(String key, Settings settings, boolean active) {
        CodeRepository repository = get(key);
        repository.update(settings.defaultBranch(), settings.testCommand(), settings.testReports(),
                settings.sandboxImage(), active);
        return repository;
    }

    public Optional<CodeRepository> find(String key) {
        return repositories.findById(key);
    }

    public CodeRepository get(String key) {
        return find(key).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No repository " + key));
    }

    /** A repository work can be started on. */
    public CodeRepository active(String key) {
        CodeRepository repository = find(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown repository " + key));
        if (!repository.active()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Repository " + key + " is disabled");
        }
        return repository;
    }

    public List<CodeRepository> all() {
        return repositories.findAllByOrderByKey();
    }
}
