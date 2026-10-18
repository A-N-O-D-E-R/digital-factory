package com.anode.factory.forge;

import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.ForgeType;

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

    /** Whether {@code login} may write to the repository: only such authors steer the factory. */
    boolean canWrite(CodeRepository repository, String login);
}
