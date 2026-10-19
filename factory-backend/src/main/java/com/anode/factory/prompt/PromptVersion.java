package com.anode.factory.prompt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;

/** One version of a role's prompt; never changed once saved. */
@Entity
@Table(name = "prompt_versions")
@IdClass(PromptVersion.Key.class)
public class PromptVersion {

    public record Key(String role, int version) implements Serializable {}

    @Id private String role;

    @Id private int version;

    @Column(nullable = false)
    private String text;

    @Column(nullable = false)
    private String note;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PromptVersion() {}

    PromptVersion(String role, int version, String text, String note, String createdBy, Instant createdAt) {
        this.role = role;
        this.version = version;
        this.text = text;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String role() {
        return role;
    }

    public int version() {
        return version;
    }

    public String text() {
        return text;
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
