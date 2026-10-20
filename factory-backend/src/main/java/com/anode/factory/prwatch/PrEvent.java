package com.anode.factory.prwatch;

/**
 * A forge event on a PR, normalized. {@code kind} is the {@code pr.event} fact: merged, closed,
 * approved, changes_requested, commented, checks_passed, checks_failed. System events (merged,
 * closed, checks) have no author to trust. {@code thread}: the forge's own id of the comment thread
 * (GitHub review comment, GitLab discussion) for replies; null for comments outside one.
 */
record PrEvent(String id, String repository, long number, String kind, String author, boolean bot, String body,
               String url, String thread, String commit) {

    PrEvent(String id, String repository, long number, String kind, String author, boolean bot, String body,
            String url, String thread) {
        this(id, repository, number, kind, author, bot, body, url, thread, null);
    }

    PrEvent(String id, String repository, long number, String kind, String author, boolean bot, String body,
            String url) {
        this(id, repository, number, kind, author, bot, body, url, null, null);
    }

    /** {@code commit}: a merge's commit, to tell later whether it was reverted. */
    PrEvent withCommit(String commit) {
        return new PrEvent(id, repository, number, kind, author, bot, body, url, thread, commit);
    }

    boolean system() {
        return author == null;
    }
}
