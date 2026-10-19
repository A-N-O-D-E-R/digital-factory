package com.anode.factory.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The overrides of one scope (global or a repository), as a JSON Merge Patch. */
@Entity
@Table(name = "settings_layers")
class SettingsLayer {

    @Id private String scope;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String document;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SettingsLayer() {}

    SettingsLayer(String scope) {
        this.scope = scope;
    }

    void update(String document, String by, Instant now) {
        this.document = document;
        this.updatedBy = by;
        this.updatedAt = now;
    }

    String scope() {
        return scope;
    }

    String document() {
        return document;
    }

    String updatedBy() {
        return updatedBy;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
