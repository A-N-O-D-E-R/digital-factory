package com.anode.factory.ticket;

import java.util.List;
import java.util.Optional;

/** Where tickets come from. One implementation per source type. */
public interface TicketSource {

    /** {@code github}, {@code gitlab}...: the prefix of its ticket keys. */
    String type();

    /** The ticket a web URL designates, if it is one of this source's. */
    Optional<TicketRef> parseUrl(String url);

    /** The repository key work on the ticket goes to by default (issues: their own repository). */
    Optional<String> repositoryOf(TicketRef ref);

    Ticket read(TicketRef ref);

    /** Open tickets of {@code project} matching {@code filter} (a label), newest first, at most {@code limit}. */
    List<Ticket> search(String project, String filter, int limit);

    void comment(TicketRef ref, String body);
}
