package com.anode.factory.forge;

import com.anode.factory.platform.IntegrationCheck;
import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.ForgeType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.AcceptMergeRequestParams;
import org.gitlab4j.api.models.AccessLevel;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.MergeRequestParams;
import org.gitlab4j.models.Constants;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** GitLab through gitlab4j (spring-boot-starter-gitlab). Merge requests are drafts by title. */
@Component
@ConditionalOnProperty(prefix = "anode.gitlab", name = "token") // as the starter's GitLabApi
class GitLabForge implements Forge, IntegrationCheck {

    private static final String DRAFT = "Draft: ";

    private final GitLabApi gitlab;
    /** The token's user, asked once. */
    private volatile String self;

    GitLabForge(GitLabApi gitlab) {
        this.gitlab = gitlab;
    }

    @Override
    public String name() {
        return "gitlab";
    }

    @Override
    public String check() throws GitLabApiException {
        return "Signed in as " + gitlab.getUserApi().getCurrentUser().getUsername() + " on "
                + gitlab.getGitLabServerUrl();
    }

    @Override
    public ForgeType type() {
        return ForgeType.GITLAB;
    }

    /** GitLab access tokens are not scoped per request: the configured token is lent for the run. */
    // ponytail: one token for every repository; project access tokens per repository if needed
    @Override
    public GitCredential credential(CodeRepository repository, Access access) {
        return new GitCredential(gitlab.getGitLabServerUrl() + "/" + repository.path() + ".git", "oauth2",
                gitlab.getAuthToken(), Instant.now().plus(1, ChronoUnit.HOURS));
    }

    @Override
    public PullRequest openDraft(CodeRepository repository, String branch, String title, String body) {
        try {
            List<MergeRequest> open = gitlab.getMergeRequestApi()
                    .getMergeRequests(repository.path(), Constants.MergeRequestState.OPENED).stream()
                    .filter(mr -> branch.equals(mr.getSourceBranch()))
                    .toList();
            MergeRequest mr = open.isEmpty()
                    ? gitlab.getMergeRequestApi().createMergeRequest(repository.path(), new MergeRequestParams()
                            .withSourceBranch(branch)
                            .withTargetBranch(repository.defaultBranch())
                            .withTitle(DRAFT + title)
                            .withDescription(body)
                            .withRemoveSourceBranch(true))
                    : open.getFirst();
            return new PullRequest(mr.getIid(), mr.getWebUrl(), branch, mr.getTitle().startsWith(DRAFT));
        } catch (GitLabApiException e) {
            throw new IllegalStateException("Cannot open a merge request on " + repository.key(), e);
        }
    }

    @Override
    public void markReady(CodeRepository repository, PullRequest pullRequest) {
        try {
            MergeRequest mr = gitlab.getMergeRequestApi().getMergeRequest(repository.path(), pullRequest.number());
            if (mr.getTitle().startsWith(DRAFT)) {
                gitlab.getMergeRequestApi().updateMergeRequest(repository.path(), pullRequest.number(),
                        new MergeRequestParams().withTitle(mr.getTitle().substring(DRAFT.length())));
            }
        } catch (GitLabApiException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void requestReview(CodeRepository repository, PullRequest pullRequest) {
        comment(repository, pullRequest, "New commits pushed by the factory — ready for another review.");
    }

    @Override
    public void merge(CodeRepository repository, PullRequest pullRequest, String commitTitle) {
        try {
            gitlab.getMergeRequestApi().acceptMergeRequest(repository.path(), pullRequest.number(),
                    new AcceptMergeRequestParams().withSquash(true).withSquashCommitMessage(commitTitle));
        } catch (GitLabApiException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean canWrite(CodeRepository repository, String login) {
        try {
            var user = gitlab.getUserApi().getUser(login);
            return user != null && gitlab.getProjectApi().getOptionalMember(repository.path(), user.getId(), true)
                    .map(member -> member.getAccessLevel().value >= AccessLevel.DEVELOPER.value)
                    .orElse(false);
        } catch (GitLabApiException e) {
            return false;
        }
    }

    /** The failed jobs of the MR's latest pipeline, with their trace. */
    @Override
    public List<FailedCheck> failedChecks(CodeRepository repository, PullRequest pullRequest) {
        try {
            var pipelines = gitlab.getMergeRequestApi().getMergeRequestPipelines(repository.path(), pullRequest.number());
            if (pipelines.isEmpty()) {
                return List.of();
            }
            List<FailedCheck> failed = new ArrayList<>();
            for (var job : gitlab.getJobApi().getJobsForPipeline(repository.path(), pipelines.getFirst().getId(),
                    Constants.JobScope.FAILED)) {
                failed.add(new FailedCheck(job.getStage() + ": " + job.getName(), job.getWebUrl(), null,
                        FailedCheck.tail(gitlab.getJobApi().getTrace(repository.path(), job.getId()))));
            }
            return failed;
        } catch (GitLabApiException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void reply(CodeRepository repository, PullRequest pullRequest, String thread, String body) {
        if (thread == null) {
            comment(repository, pullRequest, body);
            return;
        }
        try {
            gitlab.getDiscussionsApi().addMergeRequestThreadNote(repository.path(), pullRequest.number(), thread, body,
                    null);
        } catch (GitLabApiException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void resolve(CodeRepository repository, PullRequest pullRequest, String thread) {
        if (thread == null) {
            return;
        }
        try {
            gitlab.getDiscussionsApi().resolveMergeRequestDiscussion(repository.path(), pullRequest.number(), thread, true);
        } catch (GitLabApiException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ids as GitLabEvents gives the webhook deliveries of the same things. */
    @Override
    public List<PrActivity> activity(CodeRepository repository, PullRequest pullRequest) {
        try {
            String prefix = "gitlab:" + repository.path() + "!" + pullRequest.number();
            List<PrActivity> activity = new ArrayList<>();
            var mergeRequest = gitlab.getMergeRequestApi().getMergeRequest(repository.path(), pullRequest.number());
            String state = mergeRequest.getState();
            if ("merged".equals(state) || "closed".equals(state)) {
                activity.add(new PrActivity(prefix + ":" + state, state, null, false, null, null, null,
                        "merged".equals(state) ? mergeRequest.getMergeCommitSha() : null));
            }
            for (var discussion : gitlab.getDiscussionsApi().getMergeRequestDiscussions(repository.path(),
                    pullRequest.number())) {
                for (var note : discussion.getNotes()) {
                    if (Boolean.TRUE.equals(note.getSystem())) {
                        continue;
                    }
                    String username = note.getAuthor().getUsername();
                    activity.add(new PrActivity("gitlab:note:" + note.getId(), "commented", username,
                            username.contains("_bot"), note.getBody(), null, discussion.getId()));
                }
            }
            var pipelines = gitlab.getMergeRequestApi().getMergeRequestPipelines(repository.path(), pullRequest.number());
            if (!pipelines.isEmpty()) {
                String kind = switch (String.valueOf(pipelines.getFirst().getStatus()).toLowerCase()) {
                    case "success" -> "checks_passed";
                    case "failed", "canceled" -> "checks_failed";
                    default -> null;
                };
                if (kind != null) {
                    activity.add(PrActivity.system("gitlab:pipeline:" + pipelines.getFirst().getId() + ":" + kind, kind));
                }
            }
            return activity;
        } catch (GitLabApiException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String self() {
        if (self == null) {
            try {
                self = gitlab.getUserApi().getCurrentUser().getUsername();
            } catch (GitLabApiException e) {
                throw new IllegalStateException(e);
            }
        }
        return self;
    }

    @Override
    public void comment(CodeRepository repository, PullRequest pullRequest, String body) {
        try {
            gitlab.getNotesApi().createMergeRequestNote(repository.path(), pullRequest.number(), body, null, false);
        } catch (GitLabApiException e) {
            throw new IllegalStateException(e);
        }
    }
}
