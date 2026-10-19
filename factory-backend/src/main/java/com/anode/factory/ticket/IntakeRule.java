package com.anode.factory.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Which tickets of a source become work orders on a repository, and how many per pass at most. */
@Entity
@Table(name = "intake_rules")
public class IntakeRule {

    @Id private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false)
    private String project;

    /** A label for GitHub and GitLab issues. */
    @Column(nullable = false)
    private String filter;

    @Column(nullable = false)
    private String repository;

    private String workflow;

    @Column(name = "max_read", nullable = false)
    private int maxRead;

    @Column(name = "max_created", nullable = false)
    private int maxCreated;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IntakeRule() {}

    IntakeRule(String name, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    void update(String source, String project, String filter, String repository, String workflow, int maxRead,
                int maxCreated, boolean enabled) {
        this.source = source;
        this.project = project;
        this.filter = filter;
        this.repository = repository;
        this.workflow = workflow;
        this.maxRead = maxRead;
        this.maxCreated = maxCreated;
        this.enabled = enabled;
    }

    void polled(Instant now, String error) {
        this.lastPolledAt = now;
        this.lastError = error;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String source() {
        return source;
    }

    public String project() {
        return project;
    }

    public String filter() {
        return filter;
    }

    public String repository() {
        return repository;
    }

    public String workflow() {
        return workflow;
    }

    public int maxRead() {
        return maxRead;
    }

    public int maxCreated() {
        return maxCreated;
    }

    public boolean enabled() {
        return enabled;
    }

    public Instant lastPolledAt() {
        return lastPolledAt;
    }

    public String lastError() {
        return lastError;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
