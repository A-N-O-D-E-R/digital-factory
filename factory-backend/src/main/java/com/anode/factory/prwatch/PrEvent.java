package com.anode.factory.prwatch;

/**
 * A forge event on a PR, normalized. {@code kind} is the {@code pr.event} fact: merged, closed,
 * approved, changes_requested, commented, checks_passed, checks_failed. System events (merged,
 * closed, checks) have no author to trust. {@code thread}: the forge's own id of the comment thread
 * (GitHub review comment, GitLab discussion) for replies; null for comments outside one.
 */
record PrEvent(String id, String repository, long number, String kind, String author, boolean bot, String body,
               String url, String thread) {

    PrEvent(String id, String repository, long number, String kind, String author, boolean bot, String body,
            String url) {
        this(id, repository, number, kind, author, bot, body, url, null);
    }

    boolean system() {
        return author == null;
    }
}
