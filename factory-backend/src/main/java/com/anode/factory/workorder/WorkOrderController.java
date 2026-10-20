package com.anode.factory.workorder;

import com.anode.factory.run.Run;
import com.anode.factory.run.RunQueue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api")
class WorkOrderController {

    record CreateRequest(
            @NotBlank @Size(max = 255) String repository,
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 100_000) String description,
            String workflow) {}

    record WorkOrderView(UUID id, String repository, String title, String description, String workflow,
                         int workflowVersion, WorkOrderStatus status, String currentStep, int currentVisit,
                         String outcomeReason, String pauseReason, Map<String, Object> facts, String createdBy,
                         Instant createdAt, Instant updatedAt) {
        static WorkOrderView of(WorkOrder w, Optional<String> pauseReason) {
            return new WorkOrderView(w.id(), w.repository(), w.title(), w.description(), w.workflowName(),
                    w.workflowVersion(), pauseReason.isPresent() ? WorkOrderStatus.PAUSED : w.status(),
                    w.currentStep(), w.currentVisit(), w.outcomeReason(), pauseReason.orElse(null), w.facts(),
                    w.createdBy(), w.createdAt(), w.updatedAt());
        }
    }

    record RunView(UUID id, String stepId, int visit, String kind, String task, String status, int attempt,
                   String error, BigDecimal costUsd, String model, Long inputTokens, Long outputTokens,
                   Instant createdAt, Instant finishedAt) {
        static RunView of(Run r) {
            return new RunView(r.id(), r.stepId(), r.visit(), r.kind().name(), r.task(), r.status().name(),
                    r.attempt(), r.error(), r.costUsd(), r.model(), r.inputTokens(), r.outputTokens(), r.createdAt(),
                    r.finishedAt());
        }
    }

    record ApprovalView(UUID id, UUID workOrderId, String stepId, int visit, String gate, String decision,
                        String decidedBy, String comment, Instant createdAt, Instant decidedAt) {
        static ApprovalView of(Approval a) {
            return new ApprovalView(a.id(), a.workOrderId(), a.stepId(), a.visit(), a.gate(), a.decision().name(),
                    a.decidedBy(), a.comment(), a.createdAt(), a.decidedAt());
        }
    }

    record Detail(WorkOrderView workOrder, BigDecimal costUsd, List<RunView> runs, List<ApprovalView> approvals) {}

    record DecisionRequest(Approval.Decision decision, @Size(max = 2000) String comment) {}

    private final WorkOrderService service;
    private final RunQueue runs;
    private final JsonMapper json;

    WorkOrderController(WorkOrderService service, RunQueue runs, JsonMapper json) {
        this.service = service;
        this.runs = runs;
        this.json = json;
    }

    @PostMapping("/work-orders")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    ResponseEntity<WorkOrderView> create(@Valid @RequestBody CreateRequest request, Authentication author) {
        WorkOrder workOrder = service.create(request.repository(), request.title(), request.description(),
                request.workflow(), author.getName());
        return ResponseEntity.created(URI.create("/api/work-orders/" + workOrder.id()))
                .body(view(service.get(workOrder.id())));
    }

    @GetMapping("/work-orders")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    List<WorkOrderView> all() {
        // ponytail: budget queries per running work order; one grouped query if the list grows large
        return service.all().stream().map(this::view).toList();
    }

    @GetMapping("/work-orders/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    Detail get(@PathVariable UUID id) {
        List<Run> workOrderRuns = runs.runsOf(id);
        BigDecimal cost = workOrderRuns.stream().map(Run::costUsd).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Detail(view(service.get(id)), cost, workOrderRuns.stream().map(RunView::of).toList(),
                service.approvalsOf(id).stream().map(ApprovalView::of).toList());
    }

    record RunOutput(RunView run, JsonNode output, String logs) {}

    /** What a run produced (evidence, verdicts, plans): untrusted text, shown as such. */
    @GetMapping("/work-orders/{id}/runs/{runId}")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    RunOutput run(@PathVariable UUID id, @PathVariable UUID runId) {
        Run run = runs.runsOf(id).stream().filter(r -> r.id().equals(runId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new RunOutput(RunView.of(run), run.output() == null ? null : json.readTree(run.output()), run.logs());
    }

    private WorkOrderView view(WorkOrder workOrder) {
        return WorkOrderView.of(workOrder, workOrder.status() == WorkOrderStatus.RUNNING
                ? runs.pauseReason(workOrder.id()) : Optional.empty());
    }

    record ReworkRequest(String instructions, String step) {}

    /** {@code step}: where to go on from; by default the step the work order stopped at. */
    @PostMapping("/work-orders/{id}/rework")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void rework(@PathVariable UUID id, @RequestBody ReworkRequest request, Authentication user) {
        service.rework(id, request.instructions(), request.step(), user.getName());
    }

    @PostMapping("/work-orders/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable UUID id, Authentication user) {
        service.cancel(id, user.getName());
    }

    @GetMapping("/approvals")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    List<ApprovalView> pending() {
        return service.pendingApprovals().stream().map(ApprovalView::of).toList();
    }

    @PostMapping("/work-orders/{id}/approvals/{approvalId}")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decide(@PathVariable UUID id, @PathVariable UUID approvalId, @Valid @RequestBody DecisionRequest request,
                Authentication user) {
        service.decide(id, approvalId, request.decision(), request.comment(), user.getName());
    }
}
