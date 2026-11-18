package com.anode.factory.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.anode.jira.JiraClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.retry.RetryPolicy;
import tools.jackson.databind.json.JsonMapper;

class JiraIssuesTest {

    final JiraIssues jira = new JiraIssues(null, "https://acme.atlassian.net/");

    @Test
    void aTicketIsItsUrlOrItsBareKey() {
        assertThat(jira.parseUrl("https://acme.atlassian.net/browse/OPS-12")).contains(new TicketRef("jira", "OPS", "OPS-12"));
        assertThat(jira.parseUrl("OPS-12")).contains(new TicketRef("jira", "OPS", "OPS-12"));
        assertThat(jira.parseUrl("ops-12")).isEmpty();
        assertThat(jira.parseUrl("https://other.atlassian.net/browse/OPS-12")).isEmpty();
    }

    private static Map<String, Object> links(String json) throws Exception {
        return Map.of("issuelinks", JsonMapper.builder().build().readValue(json, List.class));
    }

    /** The link as Jira Cloud shows it on the blocked issue: the blocker is the inwardIssue, "is blocked by". */
    private static String blockedBy(String blockerStatusCategory) {
        return """
                [{"id":"1","type":{"name":"Blocks","inward":"is blocked by","outward":"blocks"},
                  "inwardIssue":{"key":"OPS-1","fields":{"status":{"name":"X","statusCategory":{"key":"%s"}}}}}]""".formatted(blockerStatusCategory);
    }

    @Test
    void anIssueBlockedByAnUnfinishedIssueIsBlocked() throws Exception {
        assertThat(JiraIssues.blockedByOpenIssue(links(blockedBy("new")))).isTrue();
        assertThat(JiraIssues.blockedByOpenIssue(links(blockedBy("indeterminate")))).as("in progress").isTrue();
        assertThat(JiraIssues.blockedByOpenIssue(links(blockedBy("done")))).as("resolved blocker").isFalse();
    }

    @Test
    void whatIsNotABlockerOrNotInTheAnswerIsHandledSafely() throws Exception {
        // it blocks another issue (outwardIssue): not blocked itself
        assertThat(JiraIssues.blockedByOpenIssue(links("""
                [{"type":{"name":"Blocks","inward":"is blocked by","outward":"blocks"},
                  "outwardIssue":{"key":"OPS-2","fields":{"status":{"statusCategory":{"key":"new"}}}}}]"""))).isFalse();
        // another kind of link
        assertThat(JiraIssues.blockedByOpenIssue(links("""
                [{"type":{"name":"Relates","inward":"relates to","outward":"relates to"},
                  "inwardIssue":{"key":"OPS-3","fields":{"status":{"statusCategory":{"key":"new"}}}}}]"""))).isFalse();
        // a blocker whose status is missing: wait rather than start
        assertThat(JiraIssues.blockedByOpenIssue(links("""
                [{"type":{"name":"Blocks","inward":"is blocked by"},"inwardIssue":{"key":"OPS-4"}}]"""))).isTrue();
        assertThat(JiraIssues.blockedByOpenIssue(Map.of())).isFalse();
        assertThat(JiraIssues.blockedByOpenIssue(null)).isFalse();
        assertThat(JiraIssues.blockedByOpenIssue(links("[]"))).isFalse();
    }

    @Test
    void searchLeavesOutBlockedIssuesAndAsksForTheirLinks() throws Exception {
        var sent = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/3/search/jql", ex -> {
            sent.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = ("""
                    {"issues":[
                     {"id":"1","key":"OPS-10","fields":{"summary":"Free","description":null,"issuelinks":[]}},
                     {"id":"2","key":"OPS-11","fields":{"summary":"Waits","description":null,"issuelinks":%s}},
                     {"id":"3","key":"OPS-12","fields":{"summary":"Unblocked now","description":null,"issuelinks":%s}}]}""".formatted(blockedBy("new"), blockedBy("done")))
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            var client = new JiraClient("http://127.0.0.1:" + server.getAddress().getPort(), "bot@example.org", "t",
                    RetryPolicy.builder().maxRetries(0).delay(Duration.ofMillis(1)).build());
            var found = new JiraIssues(client, "https://acme.atlassian.net").search("OPS", "labels = factory AND status = \"To Do\"", 10);

            assertThat(found).extracting(t -> t.ref().id()).containsExactly("OPS-10", "OPS-12");
            assertThat(sent.get()).contains("project = \\\"OPS\\\" AND (labels = factory AND status = \\\"To Do\\\")", "issuelinks");
        } finally {
            server.stop(0);
        }
    }
}
