package com.anode.factory.workorder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A gate's request for a human decision, for one visit of the gate. */
@Entity
@Table(name = "approvals")
public class Approval {

    public enum Decision {
        PENDING,
        APPROVED,
        REJECTED
    }

    @Id private UUID id;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "step_id", nullable = false)
    private String stepId;

    @Column(nullable = false)
    private int visit;

    @Column(nullable = false)
    private String gate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Decision decision;

    @Column(name = "decided_by")
    private String decidedBy;

    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected Approval() {}

    Approval(UUID workOrderId, String stepId, int visit, String gate, Instant now) {
        this.id = UUID.randomUUID();
        this.workOrderId = workOrderId;
        this.stepId = stepId;
        this.visit = visit;
        this.gate = gate;
        this.decision = Decision.PENDING;
        this.createdAt = now;
    }

    void decide(Decision decision, String by, String comment, Instant now) {
        this.decision = decision;
        this.decidedBy = by;
        this.comment = comment;
        this.decidedAt = now;
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

    public String gate() {
        return gate;
    }

    public Decision decision() {
        return decision;
    }

    public String decidedBy() {
        return decidedBy;
    }

    public String comment() {
        return comment;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant decidedAt() {
        return decidedAt;
    }
}
