package com.anode.factory.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A ticket turned into a work order (the latest one, after a re-import), and what was reported back on it. */
@Entity
@Table(name = "imported_tickets")
public class ImportedTicket {

    /** How far the work order went, as told on the ticket: each step is commented once. */
    public enum Progress {
        STARTED,
        PR_OPENED,
        FINISHED
    }

    @Id private String ticket;

    @Column(nullable = false)
    private String url;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "rule_id")
    private UUID ruleId;

    @Enumerated(EnumType.STRING)
    private Progress reported;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    protected ImportedTicket() {}

    ImportedTicket(String ticket, String url, UUID workOrderId, UUID ruleId, Instant now) {
        this.ticket = ticket;
        this.url = url;
        this.workOrderId = workOrderId;
        this.ruleId = ruleId;
        this.importedAt = now;
    }

    void reimported(UUID workOrderId, Instant now) {
        this.workOrderId = workOrderId;
        this.reported = null;
        this.importedAt = now;
    }

    void reported(Progress progress) {
        this.reported = progress;
    }

    public String ticket() {
        return ticket;
    }

    public String url() {
        return url;
    }

    public UUID workOrderId() {
        return workOrderId;
    }

    public UUID ruleId() {
        return ruleId;
    }

    public Progress reported() {
        return reported;
    }

    public Instant importedAt() {
        return importedAt;
    }
}
