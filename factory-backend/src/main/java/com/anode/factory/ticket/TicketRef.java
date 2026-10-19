package com.anode.factory.ticket;

/** A ticket of a source: {@code project} is the source's own (repository path, Jira project...). */
public record TicketRef(String source, String project, String id) {

    /** {@code github:acme/api#12}: what identifies the ticket in the factory. */
    public String key() {
        return source + ":" + project + "#" + id;
    }
}
