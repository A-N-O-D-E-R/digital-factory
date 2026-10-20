package com.anode.factory.eval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One pass of the fixtures through a workflow; results per fixture once all its work orders ended. */
@Entity
@Table(name = "eval_runs")
public class EvalRun {

    @Id private UUID id;

    @Column(nullable = false)
    private String workflow;

    @Column(nullable = false)
    private boolean finished;

    /** One entry per fixture: fixture, type, expect, workOrderId; then status and passed. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Map<String, Object>> results = new ArrayList<>();

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** fast (classification and analysis, judged on what they found) or complete (judged by the hidden tests). */
    @Column(nullable = false)
    private String suite;

    /** A sparse settings overlay tried instead of the current settings; null: the current ones. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String candidate;

    protected EvalRun() {}

    EvalRun(String workflow, String suite, String candidate, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.suite = suite;
        this.candidate = candidate;
        this.workflow = workflow;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    void started(Fixture fixture, UUID workOrderId) {
        results.add(new java.util.HashMap<>(Map.of("fixture", fixture.id(), "type", fixture.type().name(),
                "expect", fixture.expect(), "workOrderId", workOrderId.toString())));
    }

    void finish(List<Map<String, Object>> judged, Instant now) {
        this.results = judged;
        this.finished = true;
        this.finishedAt = now;
    }

    public String suite() {
        return suite;
    }

    public String candidate() {
        return candidate;
    }

    boolean fast() {
        return "fast".equals(suite);
    }

    public UUID id() {
        return id;
    }

    public String workflow() {
        return workflow;
    }

    public boolean finished() {
        return finished;
    }

    public List<Map<String, Object>> results() {
        return results;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }
}
