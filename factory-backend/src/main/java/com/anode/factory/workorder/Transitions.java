package com.anode.factory.workorder;

import com.anode.factory.workflow.Conditions;
import com.anode.factory.workflow.WorkflowCatalog;
import com.anode.factory.workflow.WorkflowCompiler;
import com.anode.factory.workflow.WorkflowDefinition;
import com.anode.factory.workflow.WorkflowDefinition.Outcome;
import com.anode.factory.workflow.WorkflowDefinition.Step;
import com.anode.factory.workflow.WorkflowDefinition.Transition;
import com.anode.workflow.entities.steps.responses.RouteResponse;
import com.anode.workflow.entities.steps.responses.StepResponseType;
import com.anode.workflow.entities.workflows.WorkflowContext;
import com.anode.workflow.spring.autoconfigure.runtime.ContextualRoute;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The route after each step: the first transition whose condition holds is taken; a bounded
 * transition with no visits left takes its exhausted target instead.
 */
@Component(WorkflowCompiler.ROUTE)
class Transitions implements ContextualRoute {

    /** Fact recording why the last transition was taken (shown as the outcome's reason). */
    static final String REASON_FACT = "factory.reason";

    private final CurrentWorkOrder current;
    private final WorkflowCatalog catalog;
    private final Clock clock;

    Transitions(CurrentWorkOrder current, WorkflowCatalog catalog, Clock clock) {
        this.current = current;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Override
    public RouteResponse execute(WorkflowContext context) {
        WorkOrder workOrder = current.of(context);
        WorkflowDefinition workflow = catalog.definition(workOrder.workflowName(), workOrder.workflowVersion());
        String stepId = workOrder.currentStep();
        Step step = workflow.steps().stream().filter(s -> s.id().equals(stepId)).findFirst().orElseThrow();
        Map<String, Object> facts = workOrder.facts();
        List<Transition> next = step.next();
        for (int i = 0; i < next.size(); i++) {
            Transition transition = next.get(i);
            if (!Conditions.holds(transition.when(), facts)) {
                continue;
            }
            String branch = WorkflowCompiler.branchName(i);
            String target = transition.to();
            String reason = stepId + " -> " + target + (transition.when() == null ? "" : " (condition met)");
            if (transition.maxVisits() != null && !workOrder.takeBounded(stepId + "#" + i, transition.maxVisits())) {
                branch = WorkflowCompiler.exhaustedBranchName(i);
                target = transition.exhaustedTarget();
                reason = stepId + " -> " + transition.to() + " taken " + transition.maxVisits()
                        + " times already: " + target;
            }
            workOrder.learn(Map.of(REASON_FACT, reason), clock.instant());
            if (!Outcome.isOutcome(target)) {
                workOrder.enter(target, clock.instant());
            }
            return new RouteResponse(StepResponseType.OK_PROCEED, List.of(branch), "");
        }
        throw new IllegalStateException("No transition of '" + stepId + "' holds (the last one must be unconditional)");
    }
}
