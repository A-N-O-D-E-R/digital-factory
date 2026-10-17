package com.anode.factory.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One immutable version of a named workflow. */
@Entity
@Table(name = "workflow_versions")
@IdClass(WorkflowVersion.Key.class)
public class WorkflowVersion {

    public record Key(String name, int version) implements Serializable {}

    @Id private String name;
    @Id private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String definition;

    @Column(nullable = false)
    private String note;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WorkflowVersion() {}

    WorkflowVersion(String name, int version, String definition, String note, String createdBy, Instant createdAt) {
        this.name = name;
        this.version = version;
        this.definition = definition;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String name() {
        return name;
    }

    public int version() {
        return version;
    }

    public String definition() {
        return definition;
    }

    public String note() {
        return note;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
