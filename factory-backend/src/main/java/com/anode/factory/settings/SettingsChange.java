package com.anode.factory.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Who changed a layer, when, why, and what it was before and after. */
@Entity
@Table(name = "settings_changes")
public class SettingsChange {

    @Id private UUID id;

    @Column(nullable = false)
    private String scope;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String after;

    @Column(nullable = false)
    private String reason;

    @Column(name = "changed_by", nullable = false)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected SettingsChange() {}

    SettingsChange(String scope, String before, String after, String reason, String changedBy, Instant changedAt) {
        this.id = UUID.randomUUID();
        this.scope = scope;
        this.before = before;
        this.after = after;
        this.reason = reason;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    public UUID id() {
        return id;
    }

    public String scope() {
        return scope;
    }

    public String before() {
        return before;
    }

    public String after() {
        return after;
    }

    public String reason() {
        return reason;
    }

    public String changedBy() {
        return changedBy;
    }

    public Instant changedAt() {
        return changedAt;
    }
}
