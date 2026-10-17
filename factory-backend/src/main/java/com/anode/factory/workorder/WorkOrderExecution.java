package com.anode.factory.workorder;

import com.anode.factory.run.RunFinished;
import com.anode.factory.workflow.WorkflowCatalog;
import com.anode.factory.workflow.WorkflowCompiler;
import com.anode.workflow.entities.workflows.WorkflowVariables;
import com.anode.workflow.spring.autoconfigure.runtime.WorkflowEngine;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives the workflow engine for work orders. Every operation locks the work order first, so the
 * engine never runs two operations on the same case at once; the engine's state, the work order
 * and its runs commit together.
 */
@Service
class WorkOrderExecution {

    static final String ENGINE = "factory";

    /** Something happened that may let a waiting step continue. */
    record Resume(UUID workOrderId) {}

    private final WorkOrderRepository workOrders;
    private final WorkflowCatalog catalog;
    private final WorkflowEngine engine;

    WorkOrderExecution(WorkOrderRepository workOrders, WorkflowCatalog catalog, WorkflowEngine engine) {
        this.workOrders = workOrders;
        this.catalog = catalog;
        this.engine = engine;
    }

    /** Starts the workflow of a work order created in the current transaction. */
    void start(WorkOrder workOrder) {
        workOrders.lock(workOrder.id());
        var workflow = catalog.definition(workOrder.workflowName(), workOrder.workflowVersion());
        String name = workOrder.workflowName() + "@" + workOrder.workflowVersion();
        engine.startWorkflow(workOrder.id().toString(), ENGINE, WorkflowCompiler.compile(name, workflow),
                new WorkflowVariables());
    }

    @Transactional
    public void resume(UUID workOrderId) {
        WorkOrder workOrder = workOrders.lock(workOrderId).orElseThrow();
        if (!workOrder.status().terminal()) {
            engine.resumeWorkflow(workOrderId.toString(), ENGINE, null);
        }
    }

    @ApplicationModuleListener
    void on(RunFinished event) {
        resume(event.workOrderId());
    }

    @ApplicationModuleListener
    void on(Resume event) {
        resume(event.workOrderId());
    }
}
