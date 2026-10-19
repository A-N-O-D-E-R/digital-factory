package com.anode.factory.ticket;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional ticket transitions per source and step, e.g. {@code factory.tickets.transitions.jira.started:
 * In Progress}; steps are {@code started}, {@code pr-opened} and {@code done}.
 */
@ConfigurationProperties("factory.tickets")
record TicketProperties(Map<String, Map<String, String>> transitions) {

    TicketProperties {
        transitions = transitions == null ? Map.of() : transitions;
    }

    String transition(String source, String step) {
        return transitions.getOrDefault(source, Map.of()).get(step);
    }
}
