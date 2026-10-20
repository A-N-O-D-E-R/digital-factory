package com.anode.factory.forge;

/**
 * What happened on a PR, as polling the forge sees it: {@code id} is the event id webhooks give the
 * same thing (forge-native), so that an event seen both ways counts once. {@code kind} as the
 * {@code pr.event} fact; {@code author} null for the forge's own events (merge, close, checks).
 */
public record PrActivity(String id, String kind, String author, boolean bot, String body, String url, String thread) {

    static PrActivity system(String id, String kind) {
        return new PrActivity(id, kind, null, false, null, null, null);
    }
}
