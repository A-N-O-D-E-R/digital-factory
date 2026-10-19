package com.anode.factory.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** The single row deciding whether the factory dispatches runs, and within which budgets (null: no cap). */
@Entity
@Table(name = "factory_state")
class FactoryState {

    static final int ID = 1;

    @Id private int id;

    @Column(nullable = false)
    private boolean paused;

    @Column(name = "daily_usd")
    private BigDecimal dailyUsd;

    @Column(name = "work_order_usd")
    private BigDecimal workOrderUsd;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected FactoryState() {}

    void pause(boolean paused, String by, Instant now) {
        this.paused = paused;
        this.updatedBy = by;
        this.updatedAt = now;
    }

    void budgets(BigDecimal dailyUsd, BigDecimal workOrderUsd, String by, Instant now) {
        this.dailyUsd = dailyUsd;
        this.workOrderUsd = workOrderUsd;
        this.updatedBy = by;
        this.updatedAt = now;
    }

    boolean paused() {
        return paused;
    }

    BigDecimal dailyUsd() {
        return dailyUsd;
    }

    BigDecimal workOrderUsd() {
        return workOrderUsd;
    }

    String updatedBy() {
        return updatedBy;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
