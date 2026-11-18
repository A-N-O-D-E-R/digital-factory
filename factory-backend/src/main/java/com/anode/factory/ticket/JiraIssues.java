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
import java.util.stream.Stream;
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
    /** OPS-12: what people type, Jira being the only source with such keys. */
    private static final Pattern ISSUE_KEY = Pattern.compile("(([A-Z][A-Z0-9_]+)-\\d+)");

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
        if (!m.matches()) {
            m = ISSUE_KEY.matcher(reference);
        }
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

    /**
     * Matching issues, newest first, <b>without those still blocked</b>: an issue with an "is blocked by" link to an issue that is
     * not done is left for a later pass, so a roadmap's order is respected (productDesigner exports its Tickets with "Blocks"
     * links) instead of every ticket being started in parallel. Importing one by hand ({@link #read}) is not held back.
     */
    @Override
    public List<Ticket> search(String project, String filter, int limit) {
        SearchAndReconcileRequestBean query = new SearchAndReconcileRequestBean();
        query.setJql("project = \"" + project.replace("\"", "") + "\" AND (" + filter + ") ORDER BY created DESC");
        query.setMaxResults(limit);
        query.setFields(Stream.concat(FIELDS.stream(), Stream.of("issuelinks")).toList());
        return jira.search().searchAndReconsileIssuesUsingJqlPost(query).getIssues().stream()
                .filter(issue -> !blockedByOpenIssue(issue.getFields()))
                .map(this::ticket).toList();
    }

    /**
     * Whether the issue's {@code issuelinks} hold a "Blocks" link whose blocker is not done. On the blocked issue the blocker is
     * the link's {@code inwardIssue} ("is blocked by"); its {@code status.statusCategory.key} is {@code done} once resolved. A
     * blocker whose status is not in the answer counts as open: better to wait than to start a ticket that must not start.
     */
    static boolean blockedByOpenIssue(Map<String, Object> fields) {
        if (fields == null || !(fields.get("issuelinks") instanceof List<?> links)) {
            return false;
        }
        for (Object link : links) {
            if (!(link instanceof Map<?, ?> l) || !(l.get("inwardIssue") instanceof Map<?, ?> blocker)) {
                continue;
            }
            boolean blocks = l.get("type") instanceof Map<?, ?> type
                    && ("Blocks".equalsIgnoreCase(String.valueOf(type.get("name"))) || "is blocked by".equalsIgnoreCase(String.valueOf(type.get("inward"))));
            if (blocks && !done(blocker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean done(Map<?, ?> issue) {
        return issue.get("fields") instanceof Map<?, ?> f && f.get("status") instanceof Map<?, ?> status
                && status.get("statusCategory") instanceof Map<?, ?> category && "done".equals(category.get("key"));
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
