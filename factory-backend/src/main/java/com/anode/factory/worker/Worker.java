package com.anode.factory.worker;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A host executing runs, with its own credential (revoked by deactivating it). */
@Entity
@Table(name = "workers")
public class Worker {

    @Id private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    protected Worker() {}

    Worker(String name, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.active = true;
    }

    void revoke() {
        this.active = false;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public boolean active() {
        return active;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String createdBy() {
        return createdBy;
    }
}
