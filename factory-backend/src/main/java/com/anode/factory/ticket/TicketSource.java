package com.anode.factory.ticket;

import java.util.List;
import java.util.Optional;

/** Where tickets come from. One implementation per source type. */
public interface TicketSource {

    /** {@code github}, {@code gitlab}...: the prefix of its ticket keys. */
    String type();

    /** The ticket a web URL (or the source's own short key, e.g. a Jira key) designates, if it is one of this source's. */
    Optional<TicketRef> parseUrl(String url);

    /** The repository key work on the ticket goes to by default (issues: their own repository). */
    Optional<String> repositoryOf(TicketRef ref);

    Ticket read(TicketRef ref);

    /**
     * Open tickets of {@code project} matching {@code filter} (its syntax is the source's: a label, JQL...),
     * newest first, at most {@code limit}; their body may be left out, {@link #read} has it.
     */
    List<Ticket> search(String project, String filter, int limit);

    void comment(TicketRef ref, String body);

    /** Moves the ticket to {@code status} (a status or transition name); sources without statuses ignore it. */
    default void transition(TicketRef ref, String status) {}
}
