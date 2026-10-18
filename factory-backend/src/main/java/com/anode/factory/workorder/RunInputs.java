package com.anode.factory.workorder;

import com.anode.factory.repository.CodeRepository;
import com.anode.factory.run.Run;
import com.anode.factory.run.RunStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a run receives: the work order, the facts, and the outputs of earlier steps. Acceptance
 * tests (the specifier's output) are never shown to an agent — only the evidence runs execute them.
 */
final class RunInputs {

    /** Outputs agents must not see. */
    private static final Set<String> HIDDEN_FROM_AGENTS = Set.of("specifier");

    private RunInputs() {}

    static String agent(WorkOrder workOrder, CodeRepository repository, String stepId, String role, List<Run> earlier,
                        JsonMapper json) {
        return input(workOrder, repository, stepId, role, earlier, false, json);
    }

    /** Evidence and push runs: no agent involved, they see every artifact. */
    static String evidence(WorkOrder workOrder, CodeRepository repository, String stepId, String action,
                           List<Run> earlier, JsonMapper json) {
        return input(workOrder, repository, stepId, action, earlier, true, json);
    }

    private static String input(WorkOrder workOrder, CodeRepository repository, String stepId, String task,
                                List<Run> earlier, boolean evidence, JsonMapper json) {
        Map<String, Object> artifacts = new LinkedHashMap<>();
        for (Run run : earlier) { // in creation order: the latest output of a task wins
            if (run.status() == RunStatus.SUCCEEDED && (evidence || !HIDDEN_FROM_AGENTS.contains(run.task()))) {
                artifacts.put(run.task(), json.readTree(run.output()));
            }
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("workOrder", Map.of(
                "id", workOrder.id().toString(),
                "repository", workOrder.repository(),
                "title", workOrder.title(),
                "description", workOrder.description(),
                "branch", workOrder.branch()));
        // the clone URL and its token come from /internal/runs/{id}/credential, asked just in time
        input.put("repository", Map.of(
                "key", repository.key(),
                "defaultBranch", repository.defaultBranch(),
                "testCommand", repository.testCommand(),
                "testReports", repository.testReports(),
                "sandboxImage", repository.sandboxImage()));
        input.put("step", stepId);
        input.put("task", task);
        input.put("facts", workOrder.facts());
        input.put("artifacts", artifacts);
        return json.writeValueAsString(input);
    }
}
