package com.anode.factory.forge;

import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.ForgeType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** GitHub as the tests see it: what the factory did on it, in order. */
public class FakeForge implements Forge {

    private final List<String> calls = new CopyOnWriteArrayList<>();

    private final List<String> comments = new CopyOnWriteArrayList<>();
    private final AtomicLong numbers = new AtomicLong();

    public List<String> calls() {
        return calls;
    }

    @Override
    public ForgeType type() {
        return ForgeType.GITHUB;
    }

    @Override
    public GitCredential credential(CodeRepository repository, Access access) {
        return new GitCredential("https://github.test/" + repository.path() + ".git", "x-access-token",
                access.name().toLowerCase() + "-token", Instant.now().plusSeconds(3600));
    }

    @Override
    public PullRequest openDraft(CodeRepository repository, String branch, String title, String body) {
        long number = numbers.incrementAndGet();
        calls.add("openDraft " + repository.path() + " " + branch + " -> " + repository.defaultBranch() + " #" + number);
        return new PullRequest(number, "https://github.test/" + repository.path() + "/pull/" + number, branch, true);
    }

    @Override
    public void markReady(CodeRepository repository, PullRequest pullRequest) {
        calls.add("markReady #" + pullRequest.number());
    }

    @Override
    public void requestReview(CodeRepository repository, PullRequest pullRequest) {
        calls.add("requestReview #" + pullRequest.number());
    }

    @Override
    public void merge(CodeRepository repository, PullRequest pullRequest, String commitTitle) {
        calls.add("merge #" + pullRequest.number() + " " + commitTitle);
    }

    private volatile List<FailedCheck> failedChecks = List.of();

    /** What the PRs' failed checks are from now on. */
    public void failedChecks(List<FailedCheck> checks) {
        this.failedChecks = checks;
    }

    @Override
    public List<FailedCheck> failedChecks(CodeRepository repository, PullRequest pullRequest) {
        return failedChecks;
    }

    private final Map<Long, List<PrActivity>> activity = new ConcurrentHashMap<>();

    /** What polling PR {@code number} finds from now on (ids are the forge's: one comment is on one PR). */
    public void activity(long number, List<PrActivity> activity) {
        this.activity.put(number, activity);
    }

    @Override
    public List<PrActivity> activity(CodeRepository repository, PullRequest pullRequest) {
        return activity.getOrDefault(pullRequest.number(), List.of());
    }

    /** The fake factory comments as this user. */
    @Override
    public String self() {
        return "factory-user";
    }

    /** Only "maintainer" may write to the fake repositories. */
    @Override
    public boolean canWrite(CodeRepository repository, String login) {
        return "maintainer".equals(login);
    }

    @Override
    public void comment(CodeRepository repository, PullRequest pullRequest, String body) {
        calls.add("comment #" + pullRequest.number());
        comments.add("#" + pullRequest.number() + ": " + body);
    }

    @Override
    public void reply(CodeRepository repository, PullRequest pullRequest, String thread, String body) {
        comments.add("#" + pullRequest.number() + (thread == null ? "" : " in " + thread) + ": " + body);
    }

    @Override
    public void resolve(CodeRepository repository, PullRequest pullRequest, String thread) {
        calls.add("resolve #" + pullRequest.number() + " " + thread);
    }

    /** "#number: body" (or "#number in thread: body") in order. */
    public List<String> comments() {
        return comments;
    }
}
