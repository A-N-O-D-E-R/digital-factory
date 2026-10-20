package com.anode.factory.workorder;

public enum WorkOrderStatus {
    /** A step is executing (runs queued or running, an action in progress). */
    RUNNING,
    /** A gate waits for a human decision. */
    WAITING_FOR_HUMAN,
    /** Shown, never stored: RUNNING, but its next run is held back by a budget or the factory's pause. */
    PAUSED,
    DONE,
    BLOCKED,
    FAILED,
    CANCELLED,
    /** Its PR was merged. */
    MERGED,
    /** Its PR was closed without being merged. */
    CLOSED;

    public boolean terminal() {
        return this == DONE || this == BLOCKED || this == FAILED || this == CANCELLED || this == MERGED
                || this == CLOSED;
    }
}
