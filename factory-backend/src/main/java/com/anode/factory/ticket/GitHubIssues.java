package com.anode.factory.ticket;

import com.anode.github.GitHubApp;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueQueryBuilder;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** GitHub issues, through the factory's GitHub App; the filter is a label. */
@Component
@ConditionalOnProperty(prefix = "anode.github", name = {"app-id", "private-key"}) // as the starter's GitHubApp
class GitHubIssues implements TicketSource {

    private final GitHubApp app;
    private final Pattern url;

    GitHubIssues(GitHubApp app, @Value("${factory.github.web-url:https://github.com}") String webUrl) {
        this.app = app;
        this.url = Pattern.compile(Pattern.quote(webUrl) + "/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/issues/(\\d+)/?");
    }

    @Override
    public String type() {
        return "github";
    }

    @Override
    public Optional<TicketRef> parseUrl(String reference) {
        Matcher m = url.matcher(reference);
        return m.matches() ? Optional.of(new TicketRef(type(), m.group(1), m.group(2))) : Optional.empty();
    }

    @Override
    public Optional<String> repositoryOf(TicketRef ref) {
        return Optional.of("github:" + ref.project());
    }

    private GHRepository repo(String project) throws IOException {
        String[] ownerName = project.split("/", 2);
        return app.forRepository(ownerName[0], ownerName[1]).getRepository(project);
    }

    private Ticket ticket(String project, GHIssue issue) {
        return new Ticket(new TicketRef(type(), project, Integer.toString(issue.getNumber())), issue.getTitle(),
                issue.getBody() == null ? "" : issue.getBody(), issue.getHtmlUrl().toString());
    }

    @Override
    public Ticket read(TicketRef ref) {
        try {
            return ticket(ref.project(), repo(ref.project()).getIssue(Integer.parseInt(ref.id())));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + ref.key(), e);
        }
    }

    @Override
    public List<Ticket> search(String project, String filter, int limit) {
        try {
            return repo(project).queryIssues().state(GHIssueState.OPEN).label(filter)
                    .sort(GHIssueQueryBuilder.Sort.CREATED).direction(GHDirection.DESC).pageSize(limit)
                    .list().toList().stream()
                    .filter(issue -> !issue.isPullRequest())
                    .limit(limit)
                    .map(issue -> ticket(project, issue))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot search the issues of " + project, e);
        }
    }

    @Override
    public void comment(TicketRef ref, String body) {
        try {
            repo(ref.project()).getIssue(Integer.parseInt(ref.id())).comment(body);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot comment on " + ref.key(), e);
        }
    }
}
