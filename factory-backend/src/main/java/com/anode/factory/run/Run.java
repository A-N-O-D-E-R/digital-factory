package com.anode.factory.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One execution of an agent role or evidence action for a step visit of a work order. */
@Entity
@Table(name = "runs")
public class Run {

    @Id private UUID id;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "step_id", nullable = false)
    private String stepId;

    @Column(nullable = false)
    private int visit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunKind kind;

    /** Agent role or evidence action. */
    @Column(nullable = false)
    private String task;

    /** What the output must match ({@code contracts/outputs/<schema>.schema.json}); custom roles share one. */
    @Column(name = "output_schema", nullable = false)
    private String outputSchema;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(nullable = false)
    private int attempt;

    /** Which of the step's runs this is (its place in the step's roles): retries keep it, the same role twice does not share it. */
    @Column(nullable = false)
    private int slot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String input;

    @JdbcTypeCode(SqlTypes.JSON)
    private String output;

    private String error;

    @Column(name = "worker_id")
    private UUID workerId;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    /** The most the run may spend (agent runs only): reserved when dispatched. */
    @Column(name = "budget_usd")
    private BigDecimal budgetUsd;

    /** Budget reserved when dispatched; the cost replaces it once known. */
    @Column(name = "reserved_usd")
    private BigDecimal reservedUsd;

    @Column(name = "cost_usd")
    private BigDecimal costUsd;

    /** The model(s) an agent run used, and its tokens (input with the cache, output). */
    private String model;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected Run() {}

    Run(UUID workOrderId, String stepId, int visit, int slot, RunKind kind, String task, String outputSchema,
        int attempt, String input, BigDecimal budgetUsd, Instant now) {
        this.slot = slot;
        this.outputSchema = outputSchema;
        this.id = UUID.randomUUID();
        this.budgetUsd = budgetUsd;
        this.workOrderId = workOrderId;
        this.stepId = stepId;
        this.visit = visit;
        this.kind = kind;
        this.task = task;
        this.attempt = attempt;
        this.input = input;
        this.status = RunStatus.QUEUED;
        this.createdAt = now;
    }

    void start(UUID workerId, Instant leaseUntil, BigDecimal reservedUsd, Instant now) {
        this.status = RunStatus.RUNNING;
        this.reservedUsd = reservedUsd;
        this.workerId = workerId;
        this.leaseUntil = leaseUntil;
        this.startedAt = now;
    }

    void used(String model, long inputTokens, long outputTokens) {
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    void extendLease(Instant leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    void finish(RunStatus status, String output, String error, BigDecimal costUsd, Instant now) {
        this.status = status;
        this.output = output;
        this.error = error;
        this.costUsd = costUsd;
        this.leaseUntil = null;
        this.finishedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID workOrderId() {
        return workOrderId;
    }

    public String stepId() {
        return stepId;
    }

    public int visit() {
        return visit;
    }

    public RunKind kind() {
        return kind;
    }

    public String task() {
        return task;
    }

    public String outputSchema() {
        return outputSchema;
    }

    public RunStatus status() {
        return status;
    }

    public int attempt() {
        return attempt;
    }

    public int slot() {
        return slot;
    }

    public String input() {
        return input;
    }

    public String output() {
        return output;
    }

    public String error() {
        return error;
    }

    public UUID workerId() {
        return workerId;
    }

    public BigDecimal budgetUsd() {
        return budgetUsd;
    }

    public BigDecimal costUsd() {
        return costUsd;
    }

    public String model() {
        return model;
    }

    public Long inputTokens() {
        return inputTokens;
    }

    public Long outputTokens() {
        return outputTokens;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }
}
