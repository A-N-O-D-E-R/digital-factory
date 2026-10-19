package com.anode.factory.ticket;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Issue;
import org.gitlab4j.api.models.IssueFilter;
import org.gitlab4j.models.Constants;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** GitLab issues through gitlab4j; the filter is a label. */
@Component
@ConditionalOnProperty(prefix = "anode.gitlab", name = "token") // as the starter's GitLabApi
class GitLabIssues implements TicketSource {

    private final GitLabApi gitlab;
    private final Pattern url;

    GitLabIssues(GitLabApi gitlab) {
        this.gitlab = gitlab;
        this.url = Pattern.compile(Pattern.quote(gitlab.getGitLabServerUrl())
                + "/([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+)/-/issues/(\\d+)/?");
    }

    @Override
    public String type() {
        return "gitlab";
    }

    @Override
    public Optional<TicketRef> parseUrl(String reference) {
        Matcher m = url.matcher(reference);
        return m.matches() ? Optional.of(new TicketRef(type(), m.group(1), m.group(2))) : Optional.empty();
    }

    @Override
    public Optional<String> repositoryOf(TicketRef ref) {
        return Optional.of("gitlab:" + ref.project());
    }

    private Ticket ticket(String project, Issue issue) {
        return new Ticket(new TicketRef(type(), project, Long.toString(issue.getIid())), issue.getTitle(),
                issue.getDescription() == null ? "" : issue.getDescription(), issue.getWebUrl());
    }

    @Override
    public Ticket read(TicketRef ref) {
        try {
            return ticket(ref.project(), gitlab.getIssuesApi().getIssue(ref.project(), Long.parseLong(ref.id())));
        } catch (GitLabApiException e) {
            throw new IllegalStateException("Cannot read " + ref.key(), e);
        }
    }

    @Override
    public List<Ticket> search(String project, String filter, int limit) {
        try {
            IssueFilter query = new IssueFilter().withState(Constants.IssueState.OPENED).withLabels(List.of(filter))
                    .withOrderBy(Constants.IssueOrderBy.CREATED_AT).withSort(Constants.SortOrder.DESC);
            return gitlab.getIssuesApi().getIssues(project, query, 1, limit).stream()
                    .map(issue -> ticket(project, issue))
                    .toList();
        } catch (GitLabApiException e) {
            throw new IllegalStateException("Cannot search the issues of " + project, e);
        }
    }

    @Override
    public void comment(TicketRef ref, String body) {
        try {
            gitlab.getNotesApi().createIssueNote(ref.project(), Long.parseLong(ref.id()), body);
        } catch (GitLabApiException e) {
            throw new IllegalStateException("Cannot comment on " + ref.key(), e);
        }
    }
}
