package com.anode.factory.forge;

import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.ForgeType;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** GitHub as the tests see it: what the factory did on it, in order. */
public class FakeForge implements Forge {

    private final List<String> calls = new CopyOnWriteArrayList<>();
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

    /** Only "maintainer" may write to the fake repositories. */
    @Override
    public boolean canWrite(CodeRepository repository, String login) {
        return "maintainer".equals(login);
    }

    @Override
    public void comment(CodeRepository repository, PullRequest pullRequest, String body) {
        calls.add("comment #" + pullRequest.number());
    }
}
