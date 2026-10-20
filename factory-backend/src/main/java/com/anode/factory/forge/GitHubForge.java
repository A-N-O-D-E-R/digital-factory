package com.anode.factory.forge;

import com.anode.factory.platform.IntegrationCheck;
import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.ForgeType;
import com.anode.factory.repository.Repositories;
import com.anode.github.GitHubApp;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.kohsuke.github.GHCheckRun;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPermissionType;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestReview;
import org.kohsuke.github.GHPullRequestReviewComment;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** GitHub through the factory's GitHub App (spring-boot-starter-github). */
@Component
@ConditionalOnProperty(prefix = "anode.github", name = {"app-id", "private-key"}) // as the starter's GitHubApp
class GitHubForge implements Forge, IntegrationCheck {

    private final GitHubApp app;
    private final Repositories repositories;
    private final String apiUrl;
    private final String webUrl;

    GitHubForge(GitHubApp app, Repositories repositories, @Value("${anode.github.api-url:https://api.github.com}") String apiUrl,
                @Value("${factory.github.web-url:https://github.com}") String webUrl) {
        this.app = app;
        this.repositories = repositories;
        this.apiUrl = apiUrl;
        this.webUrl = webUrl;
    }

    @Override
    public String name() {
        return "github";
    }

    /** An installation token can only be had for a repository: the first active one is read. */
    @Override
    public String check() throws IOException {
        var repository = repositories.all().stream()
                .filter(r -> r.forge() == ForgeType.GITHUB && r.active())
                .findFirst();
        if (repository.isEmpty()) {
            return "GitHub App configured; no repository registered to check it on";
        }
        return "GitHub App installed on " + repo(repository.get()).getFullName();
    }

    @Override
    public ForgeType type() {
        return ForgeType.GITHUB;
    }

    @Override
    public GitCredential credential(CodeRepository repository, Access access) {
        var permission = access == Access.WRITE ? GHPermissionType.WRITE : GHPermissionType.READ;
        var token = app.repositoryToken(repository.owner(), repository.name(), Map.of("contents", permission));
        return new GitCredential(webUrl + "/" + repository.path() + ".git", "x-access-token", token.token(),
                token.expiresAt());
    }

    private GHRepository repo(CodeRepository repository) throws IOException {
        return app.forRepository(repository.owner(), repository.name()).getRepository(repository.path());
    }

    private GHPullRequest pr(CodeRepository repository, PullRequest pullRequest) throws IOException {
        return repo(repository).getPullRequest((int) pullRequest.number());
    }

    @Override
    public PullRequest openDraft(CodeRepository repository, String branch, String title, String body) {
        try {
            GHRepository repo = repo(repository);
            List<GHPullRequest> open = repo.queryPullRequests().state(GHIssueState.OPEN)
                    .head(repository.owner() + ":" + branch).list().toList();
            GHPullRequest pr = open.isEmpty()
                    ? repo.createPullRequest(title, branch, repository.defaultBranch(), body, true, true)
                    : open.getFirst();
            return new PullRequest(pr.getNumber(), pr.getHtmlUrl().toString(), branch, pr.isDraft());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open a pull request on " + repository.key(), e);
        }
    }

    /** REST cannot take a PR out of draft: GitHub's GraphQL mutation does it. */
    @Override
    public void markReady(CodeRepository repository, PullRequest pullRequest) {
        try {
            graphql(repository).markReady(pr(repository, pullRequest).getNodeId());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void requestReview(CodeRepository repository, PullRequest pullRequest) {
        try {
            GHPullRequest pr = pr(repository, pullRequest);
            // re-request the reviewers who already reviewed
            var reviewers = pr.listReviews().toList().stream().map(review -> {
                try {
                    return review.getUser();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).distinct().toList();
            if (!reviewers.isEmpty()) {
                pr.requestReviewers(reviewers);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void merge(CodeRepository repository, PullRequest pullRequest, String commitTitle) {
        try {
            pr(repository, pullRequest).merge(commitTitle, null, GHPullRequest.MergeMethod.SQUASH);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean canWrite(CodeRepository repository, String login) {
        try {
            GHPermissionType permission = repo(repository).getPermission(login);
            return permission == GHPermissionType.ADMIN || permission == GHPermissionType.WRITE;
        } catch (IOException notACollaborator) {
            return false;
        }
    }

    /** Check runs of the head commit; GitHub Actions jobs come with their log. */
    @Override
    public List<FailedCheck> failedChecks(CodeRepository repository, PullRequest pullRequest) {
        try {
            GHRepository repo = repo(repository);
            String head = repo.getPullRequest((int) pullRequest.number()).getHead().getSha();
            List<FailedCheck> failed = new ArrayList<>();
            for (GHCheckRun run : repo.getCheckRuns(head)) {
                if (run.getConclusion() != GHCheckRun.Conclusion.FAILURE && run.getConclusion() != GHCheckRun.Conclusion.TIMED_OUT) {
                    continue;
                }
                String log = null;
                if (run.getApp() != null && "github-actions".equals(run.getApp().getSlug())) {
                    log = repo.getWorkflowJob(run.getId()).downloadLogs(in -> new String(in.readAllBytes(),
                            StandardCharsets.UTF_8));
                }
                var output = run.getOutput();
                failed.add(new FailedCheck(run.getName(), run.getHtmlUrl() == null ? null : run.getHtmlUrl().toString(),
                        output == null ? null : String.join("\n", Stream.of(output.getTitle(),
                                output.getSummary(), output.getText()).filter(Objects::nonNull).toList()),
                        FailedCheck.tail(log)));
            }
            return failed;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void reply(CodeRepository repository, PullRequest pullRequest, String thread, String body) {
        if (thread == null) {
            comment(repository, pullRequest, body);
            return;
        }
        try {
            for (GHPullRequestReviewComment comment : pr(repository, pullRequest).listReviewComments()) {
                if (Long.toString(comment.getId()).equals(thread)) {
                    comment.reply(body);
                    return;
                }
            }
            comment(repository, pullRequest, body); // the thread is gone
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Review threads are resolved through GraphQL only; a comment outside a thread (null) has none. */
    @Override
    public void resolve(CodeRepository repository, PullRequest pullRequest, String thread) {
        if (thread != null) {
            graphql(repository).resolveThread(repository.owner(), repository.name(), pullRequest.number(), thread);
        }
    }

    private GitHubGraphql graphql(CodeRepository repository) {
        String token = app.repositoryToken(repository.owner(), repository.name(),
                Map.of("pull_requests", GHPermissionType.WRITE)).token();
        return new GitHubGraphql(apiUrl, token);
    }

    /** Ids as GitHubEvents gives the webhook deliveries of the same things. */
    @Override
    public List<PrActivity> activity(CodeRepository repository, PullRequest pullRequest) {
        try {
            GHRepository repo = repo(repository);
            GHPullRequest pr = repo.getPullRequest((int) pullRequest.number());
            String prefix = "github:" + repository.path() + "#" + pullRequest.number();
            List<PrActivity> activity = new ArrayList<>();
            if (pr.isMerged()) {
                activity.add(new PrActivity(prefix + ":merged", "merged", null, false, null, null, null, pr.getMergeCommitSha()));
            } else if (pr.getState() == GHIssueState.CLOSED) {
                activity.add(PrActivity.system(prefix + ":closed", "closed"));
            }
            for (GHPullRequestReview review : pr.listReviews()) {
                String kind = switch (review.getState()) {
                    case APPROVED -> "approved";
                    case CHANGES_REQUESTED -> "changes_requested";
                    case COMMENTED -> "commented";
                    default -> null;
                };
                if (kind != null) {
                    activity.add(authored("github:review:" + review.getId(), kind, review.getUser(), review.getBody(),
                            review.getHtmlUrl(), null));
                }
            }
            for (GHIssueComment comment : pr.listComments()) {
                activity.add(authored("github:comment:" + comment.getId(), "commented", comment.getUser(),
                        comment.getBody(), comment.getHtmlUrl(), null));
            }
            for (GHPullRequestReviewComment comment : pr.listReviewComments()) {
                long thread = comment.getInReplyToId() > 0 ? comment.getInReplyToId() : comment.getId();
                activity.add(authored("github:review-comment:" + comment.getId(), "commented", comment.getUser(),
                        comment.getBody(), comment.getHtmlUrl(), Long.toString(thread)));
            }
            String head = pr.getHead().getSha();
            List<GHCheckRun> checks = repo.getCheckRuns(head).toList();
            if (!checks.isEmpty() && checks.stream().allMatch(c -> c.getStatus() == GHCheckRun.Status.COMPLETED)) {
                boolean failed = checks.stream().anyMatch(c -> c.getConclusion() == GHCheckRun.Conclusion.FAILURE
                        || c.getConclusion() == GHCheckRun.Conclusion.TIMED_OUT
                        || c.getConclusion() == GHCheckRun.Conclusion.CANCELLED);
                String kind = failed ? "checks_failed" : "checks_passed";
                activity.add(PrActivity.system("github:checks:" + head + ":" + kind + "#" + pullRequest.number(), kind));
            }
            return activity;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PrActivity authored(String id, String kind, GHUser user, String body, java.net.URL url,
                                       String thread) throws IOException {
        String login = user.getLogin();
        boolean bot = "Bot".equals(user.getType()) || login.endsWith("[bot]");
        return new PrActivity(id, kind, login, bot, body, url == null ? null : url.toString(), thread);
    }

    @Override
    public void comment(CodeRepository repository, PullRequest pullRequest, String body) {
        try {
            pr(repository, pullRequest).comment(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
