package com.anode.factory.prwatch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A forge event received for a work order's PR (once per delivery), and what the factory made of it. */
@Entity
@Table(name = "forge_events")
public class ForgeEvent {

    @Id private String id;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(nullable = false)
    private String kind;

    private String author;

    @Column(nullable = false)
    private boolean trusted;

    /** Why the event does not steer the factory, when it does not. */
    @Column(name = "ignored_because")
    private String ignoredBecause;

    /** Untrusted input: shown to agents only framed as such. */
    private String body;

    private String url;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected ForgeEvent() {}

    ForgeEvent(PrEvent event, UUID workOrderId, String ignoredBecause, Instant now) {
        this.id = event.id();
        this.workOrderId = workOrderId;
        this.kind = event.kind();
        this.author = event.author();
        this.trusted = ignoredBecause == null;
        this.ignoredBecause = ignoredBecause;
        this.body = event.body();
        this.url = event.url();
        this.receivedAt = now;
    }

    void consume(Instant now) {
        this.consumedAt = now;
    }

    public String id() {
        return id;
    }

    public UUID workOrderId() {
        return workOrderId;
    }

    public String kind() {
        return kind;
    }

    public String author() {
        return author;
    }

    public boolean trusted() {
        return trusted;
    }

    public String ignoredBecause() {
        return ignoredBecause;
    }

    public String body() {
        return body;
    }

    public String url() {
        return url;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public Instant consumedAt() {
        return consumedAt;
    }
}
