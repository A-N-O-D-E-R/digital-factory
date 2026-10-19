package com.anode.factory.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The roles agent runs play. Built-in roles and their first prompt ({@code prompts/<role>.md}) are
 * installed at the first start; custom roles are reviewers, answering like the built-in reviewer.
 */
@Service
public class AgentRoles {

    private record BuiltIn(String description, boolean writesCode) {}

    private static final Map<String, BuiltIn> BUILT_IN = Map.of(
            "classifier", new BuiltIn("Classifies the work order: type and risk", false),
            "analyst", new BuiltIn("Reads the code, reproduces bugs, plans the change", false),
            "specifier", new BuiltIn("Writes the acceptance tests hidden from the implementer", false),
            "implementer", new BuiltIn("Implements the plan in the sandbox", true),
            "reviewer", new BuiltIn("Reviews the diff", false),
            "security-reviewer", new BuiltIn("Reviews the diff for security", false),
            "feedback-triage", new BuiltIn("Triages PR feedback: change, answer, out of scope, done", false));

    /** Custom roles answer with this schema: their verdicts join the reviewers'. */
    private static final String CUSTOM_SCHEMA = "reviewer";

    private final AgentRoleRepository roles;
    private final PromptVersionRepository prompts;
    private final Clock clock;

    AgentRoles(AgentRoleRepository roles, PromptVersionRepository prompts, Clock clock) {
        this.roles = roles;
        this.prompts = prompts;
        this.clock = clock;
    }

    /** Before anything that refers to roles (the built-in workflow). */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @Transactional
    public void installBuiltIns() {
        BUILT_IN.forEach((name, role) -> {
            if (!roles.existsById(name)) {
                roles.save(new AgentRole(name, role.description(), role.writesCode(), name, true, "factory",
                        clock.instant()));
                prompts.save(new PromptVersion(name, 1, builtInPrompt(name), "Built-in prompt", "factory",
                        clock.instant()));
            }
        });
    }

    public List<AgentRole> all() {
        return roles.findAllByOrderByName();
    }

    public Set<String> names() {
        return all().stream().map(AgentRole::name).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    public boolean exists(String role) {
        return roles.existsById(role);
    }

    public boolean writesCode(String role) {
        return roles.findById(role).map(AgentRole::writesCode).orElse(false);
    }

    public String outputSchema(String role) {
        return get(role).outputSchema();
    }

    public AgentRole get(String role) {
        return roles.findById(role)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No agent role " + role));
    }

    /** A custom reviewer, with its first prompt. */
    @Transactional
    public AgentRole create(String name, String description, String prompt, String by) {
        if (name == null || !name.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role name");
        }
        if (roles.existsById(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Role " + name + " exists");
        }
        AgentRole role = roles.save(new AgentRole(name, description, false, CUSTOM_SCHEMA, false, by, clock.instant()));
        publish(name, prompt, "Created", by);
        return role;
    }

    /** A new version of the role's prompt; earlier ones never change. */
    @Transactional
    public PromptVersion publish(String role, String text, String note, String by) {
        get(role);
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A prompt is required");
        }
        if (note == null || note.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A note is required");
        }
        int version = prompts.findFirstByRoleOrderByVersionDesc(role).map(PromptVersion::version).orElse(0) + 1;
        return prompts.save(new PromptVersion(role, version, text, note, by, clock.instant()));
    }

    public Optional<PromptVersion> prompt(String role, int version) {
        return prompts.findByRoleAndVersion(role, version);
    }

    public Optional<PromptVersion> latest(String role) {
        return prompts.findFirstByRoleOrderByVersionDesc(role);
    }

    /** The latest prompt version of every role. */
    public Map<String, Integer> latestVersions() {
        Map<String, Integer> versions = new LinkedHashMap<>();
        all().forEach(role -> latest(role.name()).ifPresent(p -> versions.put(role.name(), p.version())));
        return versions;
    }

    public List<PromptVersion> history(String role) {
        get(role);
        return prompts.findByRoleOrderByVersionDesc(role);
    }

    /** Line diff against the previous version (everything added for the first one). */
    public List<String> diff(PromptVersion version) {
        String previous = prompt(version.role(), version.version() - 1).map(PromptVersion::text).orElse("");
        return LineDiff.of(previous, version.text());
    }

    private static String builtInPrompt(String role) {
        try (InputStream in = AgentRoles.class.getResourceAsStream("/prompts/" + role + ".md")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
