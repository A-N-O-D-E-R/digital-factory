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

    /** What the worker last reported: its version, run slots and runs in progress. */
    private String version;

    private String image;

    private Integer slots;

    private Integer busy;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    /** A draining worker claims no new run; it finishes its own. */
    @Column(nullable = false)
    private boolean draining;

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

    void heartbeat(String version, String image, int slots, int busy, Instant now) {
        this.version = version;
        this.image = image;
        this.slots = slots;
        this.busy = busy;
        this.lastHeartbeatAt = now;
    }

    void drain(boolean draining) {
        this.draining = draining;
    }

    public String version() {
        return version;
    }

    public String image() {
        return image;
    }

    public Integer slots() {
        return slots;
    }

    public Integer busy() {
        return busy;
    }

    public Instant lastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public boolean draining() {
        return draining;
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
