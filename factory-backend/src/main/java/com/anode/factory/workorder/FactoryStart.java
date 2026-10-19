package com.anode.factory.workorder;

import com.anode.factory.workflow.WorkflowCatalog;
import com.anode.factory.workflow.WorkflowCompiler;
import com.anode.workflow.entities.steps.responses.TaskResponse;
import com.anode.workflow.entities.workflows.WorkflowContext;
import com.anode.workflow.spring.autoconfigure.runtime.ContextualTask;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** The engine's first step: enters the workflow's start step. */
@Component(WorkflowCompiler.START)
class FactoryStart implements ContextualTask {

    private final CurrentWorkOrder current;
    private final WorkflowCatalog catalog;
    private final Clock clock;

    FactoryStart(CurrentWorkOrder current, WorkflowCatalog catalog, Clock clock) {
        this.current = current;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Override
    public TaskResponse execute(WorkflowContext context) {
        WorkOrder workOrder = current.of(context);
        String start = workOrder.reworkStep() != null ? workOrder.reworkStep()
                : catalog.definition(workOrder.workflowName(), workOrder.workflowVersion()).start();
        workOrder.enter(start, clock.instant());
        return StepResult.PROCEED.response();
    }
}
