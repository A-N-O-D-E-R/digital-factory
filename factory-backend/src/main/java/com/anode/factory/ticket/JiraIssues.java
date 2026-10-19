package com.anode.factory.ticket;

import com.anode.factory.platform.IntegrationCheck;
import com.anode.jira.JiraClient;
import com.anode.jira.JiraDocuments;
import com.anode.jira.client.model.Comment;
import com.anode.jira.client.model.IssueBean;
import com.anode.jira.client.model.IssueTransition;
import com.anode.jira.client.model.IssueUpdateDetails;
import com.anode.jira.client.model.SearchAndReconcileRequestBean;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Jira Cloud issues (spring-boot-starter-jira). A ticket's project is the Jira project key, its id
 * the issue key ({@code jira:OPS#OPS-12}); a rule's filter is JQL, within the project.
 */
@Component
@ConditionalOnProperty(prefix = "anode.jira", name = {"base-url", "email", "api-token"}) // as the starter's JiraClient
class JiraIssues implements TicketSource, IntegrationCheck {

    private static final List<String> FIELDS = List.of("summary", "description");

    private final JiraClient jira;
    private final String baseUrl;
    private final Pattern url;

    JiraIssues(JiraClient jira, @Value("${anode.jira.base-url}") String baseUrl) {
        this.jira = jira;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.url = Pattern.compile(Pattern.quote(this.baseUrl) + "/browse/(([A-Z][A-Z0-9_]+)-\\d+)/?");
    }

    @Override
    public String type() {
        return "jira";
    }

    @Override
    public String name() {
        return type();
    }

    @Override
    public String check() {
        return "Signed in as " + jira.myself().getCurrentUser(null).getDisplayName() + " on " + baseUrl;
    }

    @Override
    public Optional<TicketRef> parseUrl(String reference) {
        Matcher m = url.matcher(reference);
        return m.matches() ? Optional.of(new TicketRef(type(), m.group(2), m.group(1))) : Optional.empty();
    }

    /** A Jira issue names no repository: the rule or the request does. */
    @Override
    public Optional<String> repositoryOf(TicketRef ref) {
        return Optional.empty();
    }

    private Ticket ticket(IssueBean issue) {
        String project = issue.getKey().substring(0, issue.getKey().lastIndexOf('-'));
        Map<String, Object> fields = issue.getFields();
        return new Ticket(new TicketRef(type(), project, issue.getKey()), String.valueOf(fields.get("summary")),
                AdfText.of(fields.get("description")), baseUrl + "/browse/" + issue.getKey());
    }

    @Override
    public Ticket read(TicketRef ref) {
        return ticket(jira.issues().getIssue(ref.id(), FIELDS, null, null, null, null, null));
    }

    @Override
    public List<Ticket> search(String project, String filter, int limit) {
        SearchAndReconcileRequestBean query = new SearchAndReconcileRequestBean();
        query.setJql("project = \"" + project.replace("\"", "") + "\" AND (" + filter + ") ORDER BY created DESC");
        query.setMaxResults(limit);
        query.setFields(FIELDS);
        return jira.search().searchAndReconsileIssuesUsingJqlPost(query).getIssues().stream().map(this::ticket).toList();
    }

    @Override
    public void comment(TicketRef ref, String body) {
        Comment comment = new Comment();
        comment.setBody(JiraDocuments.text(body));
        jira.comments().addComment(ref.id(), comment, null);
    }

    /** By the transition's name or the status it leads to. */
    @Override
    public void transition(TicketRef ref, String status) {
        IssueTransition available = jira.issues().getTransitions(ref.id(), null, null, null, null, null)
                .getTransitions().stream()
                .filter(t -> status.equalsIgnoreCase(t.getName())
                        || (t.getTo() != null && status.equalsIgnoreCase(t.getTo().getName())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No transition to '" + status + "' on " + ref.id()));
        IssueTransition transition = new IssueTransition();
        transition.setId(available.getId());
        IssueUpdateDetails update = new IssueUpdateDetails();
        update.setTransition(transition);
        jira.issues().doTransition(ref.id(), update);
    }
}
