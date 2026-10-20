package com.anode.factory.workorder;

import com.anode.factory.workflow.WorkflowCatalog;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * select-workflow, after classification: the work goes on in the workflow the settings map its type
 * to (settings.workflows, layered: a repository can map types to its own), at that workflow's latest
 * version, frozen from then on. A type mapped to nothing, or to the current workflow, goes on here.
 */
@Component
class SelectWorkflowAction implements FactoryAction {

    private final WorkOrderRepository workOrders;
    private final WorkflowCatalog catalog;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Clock clock;

    SelectWorkflowAction(WorkOrderRepository workOrders, WorkflowCatalog catalog, ApplicationEventPublisher events,
                         JsonMapper json, Clock clock) {
        this.workOrders = workOrders;
        this.catalog = catalog;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "select-workflow";
    }

    @Override
    public Result execute(Context context) {
        WorkOrder workOrder = workOrders.findById(context.workOrderId()).orElseThrow();
        if (!(workOrder.facts().get("type") instanceof String type)) {
            return Result.fail("select-workflow needs the work order classified first");
        }
        JsonNode mapped = json.readTree(workOrder.settings()).path("workflows").path(type);
        if (!mapped.isString() || mapped.asString().equals(workOrder.workflowName())) {
            return Result.proceed();
        }
        var target = catalog.latest(mapped.asString());
        if (target.isEmpty()) {
            return Result.fail("type " + type + " is mapped to workflow '" + mapped.asString() + "', which does not exist");
        }
        workOrder.switchWorkflow(target.get().name(), target.get().version(), clock.instant());
        events.publishEvent(new WorkOrderExecution.Switched(workOrder.id()));
        return Result.waitForEvent(); // this case stops here; the work goes on in the new one
    }
}
