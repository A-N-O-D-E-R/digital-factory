package com.anode.factory.ticket;

/** A ticket as read from its source; title and body are untrusted input. */
public record Ticket(TicketRef ref, String title, String body, String url) {}
