package com.anode.factory.autonomy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The autonomy of one class of work orders. The level is what the admin grants; it only takes
 * effect up to merge when the measured success rate (from the evals) reaches the threshold.
 */
@Entity
@Table(name = "autonomy_policies")
public class AutonomyPolicy {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "work_order_type")
    private WorkOrderType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AutonomyLevel level;

    /** Between 0 and 1. */
    @Column(name = "min_success_rate", nullable = false)
    private BigDecimal minSuccessRate;

    /** Measured by the evals; unknown until then. */
    @Column(name = "success_rate")
    private BigDecimal successRate;

    /** Above it, among the class's latest PRs closed without merge, merging is taken back. Between 0 and 1. */
    @Column(name = "max_failure_rate", nullable = false)
    private BigDecimal maxFailureRate;

    /** Measured on the class's latest PRs; unknown until there are enough. */
    @Column(name = "failure_rate")
    private BigDecimal failureRate;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AutonomyPolicy() {}

    AutonomyPolicy(WorkOrderType type) {
        this.type = type;
    }

    void grant(AutonomyLevel level, BigDecimal minSuccessRate, BigDecimal maxFailureRate, String by, Instant now) {
        this.level = level;
        this.minSuccessRate = minSuccessRate;
        this.maxFailureRate = maxFailureRate;
        this.updatedBy = by;
        this.updatedAt = now;
    }

    void measured(BigDecimal successRate) {
        this.successRate = successRate;
    }

    /** The real-world failure rate; merging is taken back (one level down) once it is too high. */
    void failed(BigDecimal failureRate, String why, Instant now) {
        this.failureRate = failureRate;
        if (level == AutonomyLevel.MERGE && failureRate.compareTo(maxFailureRate) > 0) {
            level = AutonomyLevel.DRAFT_PR;
            updatedBy = "factory: " + why;
            updatedAt = now;
        }
    }

    public BigDecimal maxFailureRate() {
        return maxFailureRate;
    }

    public BigDecimal failureRate() {
        return failureRate;
    }

    /** Merging alone is earned: granted, and measured at least as reliable as required. */
    boolean mergeEarned() {
        return level == AutonomyLevel.MERGE && successRate != null && successRate.compareTo(minSuccessRate) >= 0;
    }

    public WorkOrderType type() {
        return type;
    }

    public AutonomyLevel level() {
        return level;
    }

    public BigDecimal minSuccessRate() {
        return minSuccessRate;
    }

    public BigDecimal successRate() {
        return successRate;
    }

    public String updatedBy() {
        return updatedBy;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
