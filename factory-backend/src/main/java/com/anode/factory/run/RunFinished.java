package com.anode.factory.run;

import java.util.UUID;

/** A run reached a final state that will not be retried. */
public record RunFinished(UUID runId, UUID workOrderId, String stepId, int visit, RunStatus status) {}
