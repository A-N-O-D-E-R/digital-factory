package com.anode.factory.workorder;

import com.anode.factory.repository.Repositories;
import com.anode.factory.run.RunQueue;
import com.anode.factory.settings.Settings;
import com.anode.factory.workflow.WorkflowCatalog;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WorkOrderService {

    private final WorkOrderRepository workOrders;
    private final ApprovalRepository approvals;
    private final WorkflowCatalog catalog;
    private final WorkOrderExecution execution;
    private final RunQueue runs;
    private final Repositories repositories;
    private final Settings settings;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    WorkOrderService(WorkOrderRepository workOrders, ApprovalRepository approvals, WorkflowCatalog catalog,
                     WorkOrderExecution execution, RunQueue runs, Repositories repositories, Settings settings,
                     JsonMapper json, ApplicationEventPublisher events, Clock clock) {
        this.settings = settings;
        this.json = json;
        this.workOrders = workOrders;
        this.approvals = approvals;
        this.catalog = catalog;
        this.execution = execution;
        this.runs = runs;
        this.repositories = repositories;
        this.events = events;
        this.clock = clock;
    }

    /** Creates a work order on the latest version of {@code workflowName} (frozen from now on) and starts it. */
    @Transactional
    public WorkOrder create(String repository, String title, String description, String workflowName, String author) {
        return create(repository, title, description, workflowName, author, null, null);
    }

    /** A work order of an eval run, judged by {@code judgeCommand} instead of being published. */
    @Transactional
    public WorkOrder create(String repository, String title, String description, String workflowName, String author,
                            UUID evalRunId, String judgeCommand) {
        repositories.active(repository);
        String frozen = json.writeValueAsString(settings.frozen(repository));
        String name = workflowName == null ? WorkflowCatalog.DEFAULT : workflowName;
        int version = catalog.latest(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "No workflow " + name))
                .version();
        WorkOrder workOrder = workOrders.save(new WorkOrder(repository, title, description, name, version, frozen, author,
                evalRunId, judgeCommand, clock.instant()));
        execution.start(workOrder);
        return workOrder;
    }

    public WorkOrder get(UUID id) {
        return workOrders.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** The work order whose PR is {@code number} on {@code repository}. */
    public Optional<WorkOrder> findByPullRequest(String repository, long number) {
        return workOrders.findByPullRequest(repository, Long.toString(number));
    }

    /** Lets a waiting step of the work order look again (once the current transaction commits). */
    @Transactional
    public void wake(UUID workOrderId) {
        events.publishEvent(new WorkOrderExecution.Resume(workOrderId));
    }

    public List<WorkOrder> idleWithOpenPullRequest(Instant before) {
        return workOrders.findIdleWithOpenPullRequest(before);
    }

    public List<WorkOrder> endedWithOpenPullRequest() {
        return workOrders.findEndedWithOpenPullRequest();
    }

    /** Real work only: eval runs have their own view. */
    public List<WorkOrder> all() {
        return workOrders.findByEvalRunIdIsNullOrderByCreatedAtDesc();
    }

    public List<WorkOrder> ofEvalRun(UUID evalRunId) {
        return workOrders.findByEvalRunIdOrderByCreatedAt(evalRunId);
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

    /** A blocked work order goes on with more instructions, from {@code step} or the step it stopped at. */
    @Transactional
    public void rework(UUID workOrderId, String instructions, String step, String by) {
        WorkOrder workOrder = workOrders.lock(workOrderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (workOrder.status() != WorkOrderStatus.BLOCKED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a blocked work order is reworked; it is "
                    + workOrder.status());
        }
        if (instructions == null || instructions.isBlank() || instructions.length() > 20_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Instructions are required (20000 characters at most)");
        }
        String from = step != null ? step : workOrder.lastStep();
        boolean exists = catalog.definition(workOrder.workflowName(), workOrder.workflowVersion()).steps().stream()
                .anyMatch(s -> s.id().equals(from));
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "No step " + from + " in its workflow");
        }
        workOrder.rework(from, instructions.strip() + "\n(by " + by + ")", clock.instant());
        execution.start(workOrder);
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
