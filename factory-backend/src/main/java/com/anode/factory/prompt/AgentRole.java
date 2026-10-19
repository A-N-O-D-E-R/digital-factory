package com.anode.factory.prompt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A role agent runs play; its output schema decides what its runs must answer and what facts they give. */
@Entity
@Table(name = "agent_roles")
public class AgentRole {

    @Id private String name;

    @Column(nullable = false)
    private String description;

    @Column(name = "writes_code", nullable = false)
    private boolean writesCode;

    @Column(name = "output_schema", nullable = false)
    private String outputSchema;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentRole() {}

    AgentRole(String name, String description, boolean writesCode, String outputSchema, boolean builtIn,
              String createdBy, Instant createdAt) {
        this.name = name;
        this.description = description;
        this.writesCode = writesCode;
        this.outputSchema = outputSchema;
        this.builtIn = builtIn;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public boolean writesCode() {
        return writesCode;
    }

    public String outputSchema() {
        return outputSchema;
    }

    public boolean builtIn() {
        return builtIn;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
