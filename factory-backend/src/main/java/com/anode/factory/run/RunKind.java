package com.anode.factory.run;

/** What a run executes in the sandbox. */
public enum RunKind {
    /** An agent role (classifier, implementer...) producing a structured output. */
    AGENT,
    /** A factory action producing evidence (run-tests, secret-scan): no agent involved. */
    EVIDENCE,
    /** Merges the default branch into the work branch: no agent, but it writes code (its bundle). */
    UPDATE,
    /** Pushes the work branch from a clean repository: the only kind given a write credential. */
    PUSH
}
