package com.anode.factory.workorder;

import com.anode.workflow.entities.workflows.WorkflowContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The work order an engine callback is about (the engine case id is the work order id). It is
 * already locked by {@link WorkOrderExecution}, which drives every engine operation.
 */
@Component
class CurrentWorkOrder {

    private final WorkOrderRepository workOrders;

    CurrentWorkOrder(WorkOrderRepository workOrders) {
        this.workOrders = workOrders;
    }

    WorkOrder of(WorkflowContext context) {
        UUID id = UUID.fromString(context.getCaseId());
        return workOrders.findById(id).orElseThrow(() -> new IllegalStateException("No work order " + id));
    }
}
