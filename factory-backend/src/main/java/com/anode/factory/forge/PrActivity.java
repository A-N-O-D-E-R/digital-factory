package com.anode.factory.forge;

/**
 * What happened on a PR, as polling the forge sees it: {@code id} is the event id webhooks give the
 * same thing (forge-native), so that an event seen both ways counts once. {@code kind} as the
 * {@code pr.event} fact; {@code author} null for the forge's own events (merge, close, checks);
 * {@code commit}: a merge's commit.
 */
public record PrActivity(String id, String kind, String author, boolean bot, String body, String url, String thread,
                         String commit) {

    public PrActivity(String id, String kind, String author, boolean bot, String body, String url, String thread) {
        this(id, kind, author, bot, body, url, thread, null);
    }

    static PrActivity system(String id, String kind) {
        return new PrActivity(id, kind, null, false, null, null, null);
    }
}
