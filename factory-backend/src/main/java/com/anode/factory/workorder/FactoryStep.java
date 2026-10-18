package com.anode.factory.workorder;

import com.anode.factory.autonomy.AutonomyPolicies;
import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.Repositories;
import com.anode.factory.run.Run;
import com.anode.factory.run.RunKind;
import com.anode.factory.run.RunQueue;
import com.anode.factory.run.RunStatus;
import com.anode.factory.workflow.Conditions;
import com.anode.factory.workflow.WorkflowCatalog;
import com.anode.factory.workflow.WorkflowCompiler;
import com.anode.factory.workflow.WorkflowDefinition.Outcome;
import com.anode.factory.workflow.WorkflowDefinition.Step;
import com.anode.workflow.entities.steps.responses.TaskResponse;
import com.anode.workflow.entities.workflows.WorkflowContext;
import com.anode.workflow.spring.autoconfigure.runtime.ContextualTask;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executes one visit of a workflow step. A step that has to wait (runs not finished, gate not
 * decided, action waiting for an event) pends; the engine runs it again when the work order is
 * resumed, and it picks up where it was from the database.
 */
@Component(WorkflowCompiler.STEP)
class FactoryStep implements ContextualTask {

    /** Actions executed by the workers rather than by the control plane. */
    private static final Map<String, RunKind> WORKER_ACTIONS = Map.of(
            "run-tests", RunKind.EVIDENCE,
            "secret-scan", RunKind.EVIDENCE,
            "push-branch", RunKind.PUSH);

    private final CurrentWorkOrder current;
    private final WorkflowCatalog catalog;
    private final RunQueue runs;
    private final Repositories repositories;
    private final ApprovalRepository approvals;
    private final AutonomyPolicies autonomy;
    private final Map<String, FactoryAction> actions;
    private final JsonMapper json;
    private final Clock clock;

    FactoryStep(CurrentWorkOrder current, WorkflowCatalog catalog, RunQueue runs, Repositories repositories,
                ApprovalRepository approvals, AutonomyPolicies autonomy, List<FactoryAction> actions, JsonMapper json,
                Clock clock) {
        this.current = current;
        this.catalog = catalog;
        this.runs = runs;
        this.repositories = repositories;
        this.approvals = approvals;
        this.autonomy = autonomy;
        this.actions = actions.stream().collect(Collectors.toMap(FactoryAction::name, Function.identity()));
        this.json = json;
        this.clock = clock;
    }

    @Override
    public TaskResponse execute(WorkflowContext context) {
        WorkOrder workOrder = current.of(context);
        if (workOrder.status().terminal()) {
            return StepResult.WAIT.response(); // ended by a step (e.g. a failed run): never moves again
        }
        String stepId = WorkflowCompiler.stepId(context.getStepName());
        Step step = catalog.definition(workOrder.workflowName(), workOrder.workflowVersion()).steps().stream()
                .filter(s -> s.id().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No step " + stepId));
        // decided afresh at every step: evidence and policies change
        boolean allows = autonomy.allowsMerge(workOrder.facts());
        if (!Boolean.valueOf(allows).equals(workOrder.facts().get("autonomy.allows"))) {
            workOrder.learn(Map.of("autonomy.allows", allows), clock.instant());
        }
        StepResult result = switch (step.kind()) {
            case agent -> runs(workOrder, step, RunKind.AGENT, step.roles());
            case gate -> gate(workOrder, step);
            case factory -> WORKER_ACTIONS.containsKey(step.action())
                    ? runs(workOrder, step, WORKER_ACTIONS.get(step.action()), List.of(step.action()))
                    : action(workOrder, step);
        };
        return result.response();
    }

    /** Queues one run per task on the first execution; proceeds once all of them succeeded. */
    private StepResult runs(WorkOrder workOrder, Step step, RunKind kind, List<String> tasks) {
        List<Run> visitRuns = runs.runsOf(workOrder.id(), step.id(), workOrder.currentVisit());
        if (visitRuns.isEmpty()) {
            List<Run> earlier = runs.runsOf(workOrder.id());
            CodeRepository repository = repositories.get(workOrder.repository());
            for (String task : tasks) {
                String input = kind == RunKind.AGENT
                        ? RunInputs.agent(workOrder, repository, step.id(), task, earlier, json)
                        : RunInputs.evidence(workOrder, repository, step.id(), task, earlier, json);
                runs.enqueue(workOrder.id(), step.id(), workOrder.currentVisit(), kind, task, input);
            }
            workOrder.resumeRunning(clock.instant());
            return StepResult.WAIT;
        }
        // retries are new rows: the latest attempt of each task decides
        Map<String, Run> latest = new LinkedHashMap<>();
        visitRuns.forEach(run -> latest.merge(run.task(), run, (a, b) -> b.attempt() >= a.attempt() ? b : a));
        if (latest.values().stream().anyMatch(run -> !run.status().finished())) {
            return StepResult.WAIT;
        }
        List<Run> unsuccessful = latest.values().stream().filter(run -> run.status() != RunStatus.SUCCEEDED).toList();
        if (!unsuccessful.isEmpty()) {
            Run run = unsuccessful.getFirst();
            workOrder.finish(Outcome.FAILED, step.id() + ": " + run.task() + " " + run.status()
                    + " after " + run.attempt() + " attempt(s): " + run.error(), clock.instant());
            return StepResult.WAIT;
        }
        workOrder.learn(Facts.of(List.copyOf(latest.values()), step.join(), workOrder.facts(), json), clock.instant());
        return StepResult.PROCEED;
    }

    /** Waits for a human decision when the gate's condition holds; passes through otherwise. */
    private StepResult gate(WorkOrder workOrder, Step step) {
        if (step.when() != null && !Conditions.holds(step.when(), workOrder.facts())) {
            workOrder.learn(Map.of("approval", "NOT_REQUIRED"), clock.instant());
            return StepResult.PROCEED;
        }
        var approval = approvals.findByWorkOrderIdAndStepIdAndVisit(workOrder.id(), step.id(), workOrder.currentVisit());
        if (approval.isEmpty()) {
            approvals.save(new Approval(workOrder.id(), step.id(), workOrder.currentVisit(), step.gate(), clock.instant()));
            workOrder.waitForHuman(clock.instant());
            return StepResult.WAIT;
        }
        if (approval.get().decision() == Approval.Decision.PENDING) {
            return StepResult.WAIT;
        }
        workOrder.learn(Map.of("approval", approval.get().decision().name()), clock.instant());
        workOrder.resumeRunning(clock.instant());
        return StepResult.PROCEED;
    }

    private StepResult action(WorkOrder workOrder, Step step) {
        FactoryAction action = actions.get(step.action());
        if (action == null) {
            workOrder.finish(Outcome.FAILED, step.id() + ": action '" + step.action() + "' is not available",
                    clock.instant());
            return StepResult.WAIT;
        }
        var context = new FactoryAction.Context(workOrder.id(), workOrder.repository(), workOrder.branch(), workOrder.title(), step.id(),
                workOrder.currentVisit(), workOrder.facts(), step.with() == null ? Map.of() : step.with());
        return switch (action.execute(context)) {
            case FactoryAction.Result.Proceed proceed -> {
                workOrder.learn(proceed.facts(), clock.instant());
                yield StepResult.PROCEED;
            }
            case FactoryAction.Result.Wait ignored -> StepResult.WAIT;
            case FactoryAction.Result.Fail fail -> {
                workOrder.finish(Outcome.FAILED, step.id() + ": " + fail.reason(), clock.instant());
                yield StepResult.WAIT;
            }
        };
    }
}
