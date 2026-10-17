package com.anode.factory.workorder;

import com.anode.factory.run.RunQueue;
import com.anode.factory.workflow.WorkflowCatalog;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkOrderService {

    private final WorkOrderRepository workOrders;
    private final ApprovalRepository approvals;
    private final WorkflowCatalog catalog;
    private final WorkOrderExecution execution;
    private final RunQueue runs;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    WorkOrderService(WorkOrderRepository workOrders, ApprovalRepository approvals, WorkflowCatalog catalog,
                     WorkOrderExecution execution, RunQueue runs, ApplicationEventPublisher events, Clock clock) {
        this.workOrders = workOrders;
        this.approvals = approvals;
        this.catalog = catalog;
        this.execution = execution;
        this.runs = runs;
        this.events = events;
        this.clock = clock;
    }

    /** Creates a work order on the latest version of {@code workflowName} (frozen from now on) and starts it. */
    @Transactional
    public WorkOrder create(String repository, String title, String description, String workflowName, String author) {
        String name = workflowName == null ? WorkflowCatalog.DEFAULT : workflowName;
        int version = catalog.latest(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "No workflow " + name))
                .version();
        WorkOrder workOrder = workOrders.save(new WorkOrder(repository, title, description, name, version, author,
                clock.instant()));
        execution.start(workOrder);
        return workOrder;
    }

    public WorkOrder get(UUID id) {
        return workOrders.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public List<WorkOrder> all() {
        return workOrders.findAllByOrderByCreatedAtDesc();
    }

    public List<Approval> approvalsOf(UUID workOrderId) {
        return approvals.findByWorkOrderIdOrderByCreatedAt(workOrderId);
    }

    public List<Approval> pendingApprovals() {
        return approvals.findByDecisionOrderByCreatedAt(Approval.Decision.PENDING);
    }

    @Transactional
    public void decide(UUID workOrderId, UUID approvalId, Approval.Decision decision, String comment, String by) {
        if (decision == Approval.Decision.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Decide APPROVED or REJECTED");
        }
        workOrders.lock(workOrderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Approval approval = approvals.findById(approvalId)
                .filter(a -> a.workOrderId().equals(workOrderId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (approval.decision() != Approval.Decision.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already decided: " + approval.decision());
        }
        approval.decide(decision, by, comment, clock.instant());
        events.publishEvent(new WorkOrderExecution.Resume(workOrderId));
    }

    @Transactional
    public void cancel(UUID workOrderId, String by) {
        WorkOrder workOrder = workOrders.lock(workOrderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (workOrder.status().terminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Work order is " + workOrder.status());
        }
        runs.cancelAll(workOrderId);
        workOrder.finish(com.anode.factory.workflow.WorkflowDefinition.Outcome.CANCELLED, "cancelled by " + by,
                clock.instant());
    }
}
