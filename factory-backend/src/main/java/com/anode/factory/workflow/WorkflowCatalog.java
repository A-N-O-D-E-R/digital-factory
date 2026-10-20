package com.anode.factory.workflow;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** The workflows: immutable, numbered versions, validated before they are stored. */
@Service
public class WorkflowCatalog {

    public static final String DEFAULT = "default";
    /** The fast evaluation suite's workflow: classification and analysis only. */
    public static final String EVAL_FAST = "eval-fast";

    private final WorkflowVersionRepository versions;
    private final WorkflowValidator validator;
    private final Clock clock;
    private final JsonMapper json;
    private final Map<WorkflowVersion.Key, WorkflowDefinition> parsed = new ConcurrentHashMap<>();

    WorkflowCatalog(WorkflowVersionRepository versions, WorkflowValidator validator, Clock clock, JsonMapper json) {
        this.versions = versions;
        this.validator = validator;
        this.clock = clock;
        this.json = json;
    }

    /** A published version, parsed (versions are immutable, so this is cached). */
    public WorkflowDefinition definition(String name, int version) {
        return parsed.computeIfAbsent(new WorkflowVersion.Key(name, version), key -> json.readValue(
                versions.findByNameAndVersion(name, version)
                        .orElseThrow(() -> new IllegalArgumentException("No workflow " + name + " v" + version))
                        .definition(),
                WorkflowDefinition.class));
    }

    public WorkflowValidator.Result validate(String definitionJson) {
        return validator.validate(definitionJson);
    }

    /**
     * Stores {@code definitionJson} as the next version of {@code name}.
     *
     * @throws InvalidWorkflowException listing what is wrong
     */
    @Transactional
    public WorkflowVersion publish(String name, String definitionJson, String note, String author) {
        var result = validator.validate(definitionJson);
        if (!result.valid()) {
            throw new InvalidWorkflowException(result.errors());
        }
        int next = versions.findFirstByNameOrderByVersionDesc(name).map(WorkflowVersion::version).orElse(0) + 1;
        try {
            return versions.saveAndFlush(new WorkflowVersion(name, next, definitionJson, note, author, clock.instant()));
        } catch (DataIntegrityViolationException concurrentPublish) {
            throw new IllegalStateException("Workflow '" + name + "' was published concurrently, retry", concurrentPublish);
        }
    }

    public Optional<WorkflowVersion> latest(String name) {
        return versions.findFirstByNameOrderByVersionDesc(name);
    }

    public Optional<WorkflowVersion> version(String name, int version) {
        return versions.findByNameAndVersion(name, version);
    }

    public List<WorkflowVersion> history(String name) {
        return versions.findByNameOrderByVersionDesc(name);
    }

    public List<WorkflowVersion> latestOfEach() {
        return versions.findLatestVersions();
    }

    /** The built-in workflow exists from the first start on. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void installDefault() {
        for (String name : List.of(DEFAULT, EVAL_FAST)) {
            if (versions.findFirstByNameOrderByVersionDesc(name).isEmpty()) {
                publish(name, builtIn(name), "Built-in workflow", "factory");
            }
        }
    }

    static String builtIn() {
        return builtIn(DEFAULT);
    }

    static String builtIn(String name) {
        try (InputStream in = WorkflowCatalog.class.getResourceAsStream("/workflows/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
