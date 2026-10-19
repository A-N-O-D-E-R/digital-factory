package com.anode.factory.workflow;

import com.anode.factory.workflow.WorkflowDefinition.Outcome;
import com.anode.factory.workflow.WorkflowDefinition.Step;
import com.anode.factory.workflow.WorkflowDefinition.Transition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The path a work order would take through a workflow, given what each step would learn: the same
 * transitions, conditions and bounds as the execution, nothing run.
 */
final class WorkflowDryRun {

    /** {@code next}: a step id or an outcome ("@done"...). */
    record Visit(String step, int visit, Map<String, Object> learnt, String next, String reason) {}

    record Path(List<Visit> path, String outcome, String reason) {}

    /** A valid workflow ends; this only stops a dry run whose facts never let a bounded loop run out. */
    private static final int MAX_VISITS = 500;

    private WorkflowDryRun() {}

    /**
     * {@code learnt}: per step, what each of its visits learns (the last one again for later visits);
     * a gate whose condition holds learns {@code approval} APPROVED unless told otherwise.
     */
    static Path run(WorkflowDefinition workflow, Map<String, Object> initial, Map<String, List<Map<String, Object>>> learnt) {
        Map<String, Step> steps = new LinkedHashMap<>();
        workflow.steps().forEach(step -> steps.put(step.id(), step));
        Map<String, Object> facts = new HashMap<>(initial);
        Map<String, Integer> visits = new HashMap<>();
        Map<String, Integer> taken = new HashMap<>();
        List<Visit> path = new ArrayList<>();
        String current = workflow.start();
        while (path.size() < MAX_VISITS) {
            Step step = steps.get(current);
            int visit = visits.merge(current, 1, Integer::sum);
            List<Map<String, Object>> given = learnt.getOrDefault(current, List.of());
            Map<String, Object> learns = new LinkedHashMap<>(given.isEmpty() ? Map.of()
                    : given.get(Math.min(visit, given.size()) - 1));
            if (step.kind() == WorkflowDefinition.Kind.gate && !learns.containsKey("approval")) {
                learns.put("approval", step.when() == null || Conditions.holds(step.when(), facts) ? "APPROVED"
                        : "NOT_REQUIRED");
            }
            facts.putAll(learns);
            String next = null;
            String reason = null;
            for (int i = 0; i < step.next().size() && next == null; i++) {
                Transition transition = step.next().get(i);
                if (!Conditions.holds(transition.when(), facts)) {
                    continue;
                }
                next = transition.to();
                reason = transition.when() == null ? "unconditional" : "condition met";
                if (transition.maxVisits() != null) {
                    int times = taken.merge(current + "#" + i, 1, Integer::sum);
                    if (times > transition.maxVisits()) {
                        next = transition.exhaustedTarget();
                        reason = "taken " + transition.maxVisits() + " times already";
                    }
                }
            }
            if (next == null) {
                return new Path(path, null, "No transition of '" + current + "' holds");
            }
            path.add(new Visit(current, visit, learns, next, reason));
            if (Outcome.isOutcome(next)) {
                return new Path(path, next, null);
            }
            current = next;
        }
        return new Path(path, null, "Stopped after " + MAX_VISITS + " visits: do the facts ever end its loops?");
    }
}
