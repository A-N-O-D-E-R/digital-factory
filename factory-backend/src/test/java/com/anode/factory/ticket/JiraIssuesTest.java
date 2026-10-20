package com.anode.factory.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JiraIssuesTest {

    final JiraIssues jira = new JiraIssues(null, "https://acme.atlassian.net/");

    @Test
    void aTicketIsItsUrlOrItsBareKey() {
        assertThat(jira.parseUrl("https://acme.atlassian.net/browse/OPS-12")).contains(new TicketRef("jira", "OPS", "OPS-12"));
        assertThat(jira.parseUrl("OPS-12")).contains(new TicketRef("jira", "OPS", "OPS-12"));
        assertThat(jira.parseUrl("ops-12")).isEmpty();
        assertThat(jira.parseUrl("https://other.atlassian.net/browse/OPS-12")).isEmpty();
    }
}
