package com.anode.factory.prwatch;

/**
 * A forge event on a PR, normalized. {@code kind} is the {@code pr.event} fact: merged, closed,
 * approved, changes_requested, commented, checks_passed, checks_failed. System events (merged,
 * closed, checks) have no author to trust.
 */
record PrEvent(String id, String repository, long number, String kind, String author, boolean bot, String body,
               String url) {

    boolean system() {
        return author == null;
    }
}
