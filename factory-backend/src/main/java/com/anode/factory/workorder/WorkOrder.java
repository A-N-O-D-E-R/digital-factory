package com.anode.factory.workorder;

import com.anode.factory.workflow.WorkflowDefinition.Outcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A unit of work, from request to pull request. Its workflow version is frozen at creation; the
 * facts gathered by its steps drive the transitions.
 */
@Entity
@Table(name = "work_orders")
public class WorkOrder {

    @Id private UUID id;

    @Column(nullable = false)
    private String repository;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String description;

    @Column(name = "workflow_name", nullable = false)
    private String workflowName;

    @Column(name = "workflow_version", nullable = false)
    private int workflowVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkOrderStatus status;

    @Column(name = "current_step")
    private String currentStep;

    @Column(name = "current_visit", nullable = false)
    private int currentVisit;

    @Column(name = "outcome_reason")
    private String outcomeReason;

    /** The step entered last: where a blocked work order stopped. */
    @Column(name = "last_step")
    private String lastStep;

    /** Times the work order was reworked: each rework is a new engine case, started at {@link #reworkStep}. */
    @Column(nullable = false)
    private int reworks;

    @Column(name = "rework_step")
    private String reworkStep;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> facts = new HashMap<>();

    /** Times each step was entered. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Integer> visits = new HashMap<>();

    /** Times each bounded transition ("step#index") was taken. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transition_counts", nullable = false)
    private Map<String, Integer> transitionCounts = new HashMap<>();

    /** The effective settings when created: changing a setting never alters work in flight. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String settings;

    /** Set on the work orders of an eval run: gates approve themselves, publishing is replaced by the judge. */
    @Column(name = "eval_run_id")
    private UUID evalRunId;

    /** The fixture's hidden tests: only ever in the judge run's input. */
    @Column(name = "judge_command")
    private String judgeCommand;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkOrder() {}

    WorkOrder(String repository, String title, String description, String workflowName, int workflowVersion,
              String settings, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.settings = settings;
        this.repository = repository;
        this.title = title;
        this.description = description;
        this.workflowName = workflowName;
        this.workflowVersion = workflowVersion;
        this.status = WorkOrderStatus.RUNNING;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    WorkOrder(String repository, String title, String description, String workflowName, int workflowVersion,
              String settings, String createdBy, UUID evalRunId, String judgeCommand, Instant now) {
        this(repository, title, description, workflowName, workflowVersion, settings, createdBy, now);
        this.evalRunId = evalRunId;
        this.judgeCommand = judgeCommand;
    }

    /** Enters {@code step}: a new visit of it begins. */
    void enter(String step, Instant now) {
        int visit = visits.merge(step, 1, Integer::sum);
        currentStep = step;
        lastStep = step;
        currentVisit = visit;
        status = WorkOrderStatus.RUNNING;
        updatedAt = now;
    }

    /** Takes a bounded transition if it has visits left. */
    boolean takeBounded(String key, int maxVisits) {
        int taken = transitionCounts.getOrDefault(key, 0);
        if (taken >= maxVisits) {
            return false;
        }
        transitionCounts.put(key, taken + 1);
        return true;
    }

    void learn(Map<String, Object> newFacts, Instant now) {
        facts.putAll(newFacts);
        updatedAt = now;
    }

    void waitForHuman(Instant now) {
        status = WorkOrderStatus.WAITING_FOR_HUMAN;
        updatedAt = now;
    }

    void resumeRunning(Instant now) {
        status = WorkOrderStatus.RUNNING;
        updatedAt = now;
    }

    /** A blocked work order goes on from {@code step}, its facts and visits kept, the instructions learnt. */
    void rework(String step, String instructions, Instant now) {
        reworks++;
        reworkStep = step;
        facts.put("rework.instructions", instructions);
        status = WorkOrderStatus.RUNNING;
        outcomeReason = null;
        updatedAt = now;
    }

    /** The work goes on in another workflow (chosen by its type), from that workflow's start, as a new engine case. */
    void switchWorkflow(String name, int version, Instant now) {
        workflowName = name;
        workflowVersion = version;
        reworks++;
        reworkStep = null;
        updatedAt = now;
    }

    /** The engine case of the work order: its id, then one per rework or workflow switch. */
    String caseId() {
        return reworks == 0 ? id.toString() : id + "#" + reworks;
    }

    String lastStep() {
        return lastStep;
    }

    String reworkStep() {
        return reworkStep;
    }

    void finish(Outcome outcome, String reason, Instant now) {
        status = switch (outcome) {
            case DONE -> WorkOrderStatus.DONE;
            case BLOCKED -> WorkOrderStatus.BLOCKED;
            case FAILED -> WorkOrderStatus.FAILED;
            case CANCELLED -> WorkOrderStatus.CANCELLED;
            case MERGED -> WorkOrderStatus.MERGED;
            case CLOSED -> WorkOrderStatus.CLOSED;
        };
        outcomeReason = reason;
        currentStep = null;
        updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public String repository() {
        return repository;
    }

    public UUID evalRunId() {
        return evalRunId;
    }

    public boolean eval() {
        return evalRunId != null;
    }

    String judgeCommand() {
        return judgeCommand;
    }

    /** The branch the factory pushes the work to, and opens the PR from. */
    public String branch() {
        return "factory/" + id;
    }

    public String settings() {
        return settings;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public String workflowName() {
        return workflowName;
    }

    public int workflowVersion() {
        return workflowVersion;
    }

    public WorkOrderStatus status() {
        return status;
    }

    public String currentStep() {
        return currentStep;
    }

    public int currentVisit() {
        return currentVisit;
    }

    public String outcomeReason() {
        return outcomeReason;
    }

    public Map<String, Object> facts() {
        return Map.copyOf(facts);
    }

    public Map<String, Integer> visits() {
        return Map.copyOf(visits);
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
