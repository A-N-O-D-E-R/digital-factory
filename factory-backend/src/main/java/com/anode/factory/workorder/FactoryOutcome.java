package com.anode.factory.workorder;

import com.anode.factory.autonomy.AutonomyPolicies;
import com.anode.factory.workflow.WorkflowCompiler;
import com.anode.factory.workflow.WorkflowDefinition.Outcome;
import com.anode.workflow.entities.steps.responses.TaskResponse;
import com.anode.workflow.entities.workflows.WorkflowContext;
import com.anode.workflow.spring.autoconfigure.runtime.ContextualTask;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** An outcome step: the work order ends there. */
@Component(WorkflowCompiler.OUTCOME)
class FactoryOutcome implements ContextualTask {

    private final CurrentWorkOrder current;
    private final AutonomyPolicies autonomy;
    private final Clock clock;

    FactoryOutcome(CurrentWorkOrder current, AutonomyPolicies autonomy, Clock clock) {
        this.current = current;
        this.autonomy = autonomy;
        this.clock = clock;
    }

    @Override
    public TaskResponse execute(WorkflowContext context) {
        WorkOrder workOrder = current.of(context);
        Outcome outcome = java.util.Arrays.stream(Outcome.values())
                .filter(o -> o.target().equals(context.getStepName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown outcome step " + context.getStepName()));
        workOrder.finish(outcome, (String) workOrder.facts().get(Transitions.REASON_FACT), clock.instant());
        Object prEvent = workOrder.facts().get("pr.event");
        if (!workOrder.eval() && workOrder.facts().get("type") instanceof String type
                && ("merged".equals(prEvent) || "closed".equals(prEvent))) {
            autonomy.prOutcome(workOrder.id(), type, "merged".equals(prEvent),
                    (String) workOrder.facts().get("pr.mergeCommit"), workOrder.visits().getOrDefault("triage", 0));
        }
        return StepResult.PROCEED.response();
    }
}
