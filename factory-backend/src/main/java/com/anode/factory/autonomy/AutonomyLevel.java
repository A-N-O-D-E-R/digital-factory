package com.anode.factory.autonomy;

/** Where the factory stops for a class of work orders; without a policy, at the draft PR. */
public enum AutonomyLevel {
    DRAFT_PR,
    // ponytail: no READY_PR level yet; it needs its own fact (autonomy.ready) in the workflow schema
    MERGE
}
