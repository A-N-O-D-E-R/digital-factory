package com.anode.factory.workflow;

import com.anode.factory.workflow.WorkflowDefinition.Outcome;
import com.anode.factory.workflow.WorkflowDefinition.Step;
import com.anode.factory.workflow.WorkflowDefinition.Transition;
import com.anode.factory.prompt.AgentRoles;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Refuses workflows that cannot run safely: the JSON Schema first, then the graph rules — every
 * target exists, every step is reachable and can reach an outcome, every loop is bounded, and code
 * written by an agent is always tested before it is pushed, proposed or merged.
 */
@Component
public class WorkflowValidator {

    /** Actions that publish the work: they must be preceded by evidence. */
    private static final Set<String> PUBLISHING = Set.of("push-branch", "open-draft-pr", "merge");

    private static final String EVIDENCE = "run-tests";

    private final Schema schema;
    private final JsonMapper json;
    private final AgentRoles roles;

    public WorkflowValidator(JsonMapper json, AgentRoles roles) {
        this.roles = roles;
        this.json = json;
        try (InputStream in = getClass().getResourceAsStream("/contracts/workflow-definition.schema.json")) {
            // messages are part of the API: always in English, whatever the server locale
            var config = SchemaRegistryConfig.builder().locale(Locale.ENGLISH).build();
            this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                            builder -> builder.schemaRegistryConfig(config))
                    .getSchema(in, InputFormat.JSON);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A definition and what is wrong with it (nothing when valid). */
    public record Result(WorkflowDefinition definition, List<String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public Result validate(String definitionJson) {
        List<String> schemaErrors = schema.validate(definitionJson, InputFormat.JSON).stream()
                .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                .toList();
        if (!schemaErrors.isEmpty()) {
            return new Result(null, schemaErrors);
        }
        WorkflowDefinition definition = json.readValue(definitionJson, WorkflowDefinition.class);
        return new Result(definition, checkGraph(definition));
    }

    private List<String> checkGraph(WorkflowDefinition definition) {
        List<String> errors = new ArrayList<>();
        Map<String, Step> steps = new LinkedHashMap<>();
        for (Step step : definition.steps()) {
            if (steps.putIfAbsent(step.id(), step) != null) {
                errors.add("step '" + step.id() + "' is defined more than once");
            }
        }
        if (!steps.containsKey(definition.start())) {
            errors.add("start step '" + definition.start() + "' does not exist");
        }
        for (Step step : steps.values()) {
            checkTransitions(step, steps, errors);
            if (step.kind() == WorkflowDefinition.Kind.agent) {
                step.roles().stream()
                        .filter(role -> !roles.exists(role))
                        .forEach(role -> errors.add("step '" + step.id() + "': unknown agent role '" + role
                                + "' (known: " + roles.names() + ")"));
            }
        }
        if (!errors.isEmpty()) {
            return errors; // the graph rules assume valid references
        }
        checkReachability(definition.start(), steps, errors);
        checkLoopsAreBounded(steps, errors);
        checkEvidenceBeforePublishing(steps, errors);
        checkMergeOnlyThroughAutonomy(definition.start(), steps, errors);
        return errors;
    }

    private static void checkTransitions(Step step, Map<String, Step> steps, List<String> errors) {
        List<Transition> next = step.next();
        for (int i = 0; i < next.size(); i++) {
            Transition transition = next.get(i);
            String where = "step '" + step.id() + "' next[" + i + "]";
            for (String target : List.of(transition.to(), transition.exhaustedTarget())) {
                if (!Outcome.isOutcome(target) && !steps.containsKey(target)) {
                    errors.add(where + " leads to unknown step '" + target + "'");
                }
            }
            boolean last = i == next.size() - 1;
            if (transition.when() == null && !last) {
                errors.add(where + " is unconditional: the transitions after it can never be taken");
            }
            if (transition.when() != null && last) {
                errors.add(where + " is the last transition and has a condition: give the step an unconditional way out");
            }
        }
    }

    /** Every step reachable from the start, and from every step an outcome reachable. */
    private static void checkReachability(String start, Map<String, Step> steps, List<String> errors) {
        Set<String> reached = new HashSet<>();
        var queue = new ArrayDeque<String>(List.of(start));
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (Outcome.isOutcome(id) || !reached.add(id)) {
                continue;
            }
            for (Transition t : steps.get(id).next()) {
                queue.add(t.to());
                queue.add(t.exhaustedTarget());
            }
        }
        steps.keySet().stream().filter(id -> !reached.contains(id))
                .forEach(id -> errors.add("step '" + id + "' can never be reached from '" + start + "'"));

        // an outcome is reachable from a step if one of its targets is an outcome or such a step
        Set<String> terminating = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Step step : steps.values()) {
                if (terminating.contains(step.id())) {
                    continue;
                }
                boolean ends = step.next().stream().anyMatch(t -> leadsToEnd(t.to(), terminating)
                        || (t.maxVisits() != null && leadsToEnd(t.exhaustedTarget(), terminating)));
                if (ends) {
                    terminating.add(step.id());
                    changed = true;
                }
            }
        }
        steps.keySet().stream().filter(id -> !terminating.contains(id))
                .forEach(id -> errors.add("step '" + id + "' can never reach an outcome (@done, @blocked...)"));
    }

    private static boolean leadsToEnd(String target, Set<String> terminating) {
        return Outcome.isOutcome(target) || terminating.contains(target);
    }

    /** Without its bounded transitions (maxVisits), the graph must have no cycle. */
    private static void checkLoopsAreBounded(Map<String, Step> steps, List<String> errors) {
        Map<String, List<String>> unbounded = new HashMap<>();
        steps.values().forEach(step -> unbounded.put(step.id(), step.next().stream()
                .filter(t -> t.maxVisits() == null && !Outcome.isOutcome(t.to()))
                .map(Transition::to)
                .toList()));
        Set<String> done = new HashSet<>();
        for (String id : steps.keySet()) {
            List<String> cycle = findCycle(id, unbounded, new ArrayList<>(), new HashSet<>(), done);
            if (cycle != null) {
                errors.add("loop " + String.join(" -> ", cycle)
                        + " is unbounded: give one of its transitions a maxVisits");
                return;
            }
        }
    }

    private static List<String> findCycle(
            String id, Map<String, List<String>> graph, List<String> path, Set<String> onPath, Set<String> done) {
        if (onPath.contains(id)) {
            List<String> cycle = new ArrayList<>(path.subList(path.indexOf(id), path.size()));
            cycle.add(id);
            return cycle;
        }
        if (!done.add(id)) {
            return null;
        }
        path.add(id);
        onPath.add(id);
        for (String next : graph.get(id)) {
            List<String> cycle = findCycle(next, graph, path, onPath, done);
            if (cycle != null) {
                return cycle;
            }
        }
        path.removeLast();
        onPath.remove(id);
        return null;
    }

    /** A merge step is only entered through a transition requiring {@code autonomy.allows}. */
    private static void checkMergeOnlyThroughAutonomy(String start, Map<String, Step> steps, List<String> errors) {
        Set<String> merges = new HashSet<>();
        steps.values().stream()
                .filter(step -> step.kind() == WorkflowDefinition.Kind.factory && "merge".equals(step.action()))
                .forEach(step -> merges.add(step.id()));
        if (merges.contains(start)) {
            errors.add("step '" + start + "' (merge) cannot be the start: merging requires the autonomy policy");
        }
        for (Step step : steps.values()) {
            for (int i = 0; i < step.next().size(); i++) {
                Transition t = step.next().get(i);
                if ((merges.contains(t.to()) || merges.contains(t.exhaustedTarget())) && !requiresAutonomy(t.when())) {
                    errors.add("step '" + step.id() + "' next[" + i + "] leads to a merge without requiring "
                            + "{ \"fact\": \"autonomy.allows\", \"equals\": true }");
                }
            }
        }
    }

    private static boolean requiresAutonomy(Condition when) {
        return switch (when) {
            case Condition.Fact fact -> "autonomy.allows".equals(fact.fact()) && Boolean.TRUE.equals(fact.equalTo());
            case Condition.AllOf all -> all.allOf().stream().anyMatch(WorkflowValidator::requiresAutonomy);
            case null, default -> false;
        };
    }

    /**
     * From a step that writes code (a code-writing role, or the update of the branch), no path may
     * reach a publishing action without running the tests.
     */
    private void checkEvidenceBeforePublishing(Map<String, Step> steps, List<String> errors) {
        for (Step writer : steps.values()) {
            boolean writes = writer.kind() == WorkflowDefinition.Kind.agent
                    ? writer.roles().stream().anyMatch(roles::writesCode)
                    : writer.kind() == WorkflowDefinition.Kind.factory && "update-branch".equals(writer.action());
            if (!writes) {
                continue;
            }
            Set<String> seen = new HashSet<>();
            var queue = new ArrayDeque<String>();
            writer.next().forEach(t -> {
                queue.add(t.to());
                queue.add(t.exhaustedTarget());
            });
            while (!queue.isEmpty()) {
                String id = queue.poll();
                if (Outcome.isOutcome(id) || !seen.add(id)) {
                    continue;
                }
                Step step = steps.get(id);
                if (step.kind() == WorkflowDefinition.Kind.factory && EVIDENCE.equals(step.action())) {
                    continue; // the change is proven here
                }
                if (step.kind() == WorkflowDefinition.Kind.factory && PUBLISHING.contains(step.action())) {
                    errors.add("step '" + id + "' (" + step.action() + ") can follow '" + writer.id()
                            + "' without the change being tested: put a run-tests step in between");
                    continue;
                }
                step.next().forEach(t -> {
                    queue.add(t.to());
                    queue.add(t.exhaustedTarget());
                });
            }
        }
    }
}
