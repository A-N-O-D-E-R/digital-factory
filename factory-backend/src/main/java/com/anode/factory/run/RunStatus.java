package com.anode.factory.run;

public enum RunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    /** The worker reported an error, or the lease expired; may be retried as a new attempt. */
    FAILED,
    /** The output did not match its schema; may be retried as a new attempt. */
    INVALID_OUTPUT,
    CANCELLED;

    public boolean finished() {
        return this != QUEUED && this != RUNNING;
    }
}
