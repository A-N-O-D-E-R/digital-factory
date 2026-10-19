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
    CANCELLED;

    public boolean terminal() {
        return this == DONE || this == BLOCKED || this == FAILED || this == CANCELLED;
    }
}
