package com.anode.factory.forge;

import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.ForgeType;
import java.util.List;

/** What the factory does on a code forge. One implementation per {@link ForgeType}. */
public interface Forge {

    ForgeType type();

    /** Git credentials limited to {@code repository} (and, where the forge allows, to {@code access}). */
    GitCredential credential(CodeRepository repository, Access access);

    /** Opens a draft PR from {@code branch} to the default branch; returns the existing one if already open. */
    PullRequest openDraft(CodeRepository repository, String branch, String title, String body);

    void markReady(CodeRepository repository, PullRequest pullRequest);

    /** Asks for review again (after the factory pushed new commits). */
    void requestReview(CodeRepository repository, PullRequest pullRequest);

    /** Squash-merges the PR. */
    void merge(CodeRepository repository, PullRequest pullRequest, String commitTitle);

    void comment(CodeRepository repository, PullRequest pullRequest, String body);

    /** Replies in the comment thread {@code thread} (the forge's id), or on the PR when it is null. */
    void reply(CodeRepository repository, PullRequest pullRequest, String thread, String body);

    /** Marks the thread resolved, where the forge allows it. */
    void resolve(CodeRepository repository, PullRequest pullRequest, String thread);

    /** The checks of the PR's head that failed, with the end of their logs where the forge has them. */
    List<FailedCheck> failedChecks(CodeRepository repository, PullRequest pullRequest);

    /** Whether {@code login} may write to the repository: only such authors steer the factory. */
    boolean canWrite(CodeRepository repository, String login);
}
