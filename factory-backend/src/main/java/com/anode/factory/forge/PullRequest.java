package com.anode.factory.forge;

/** A pull request (GitHub) or merge request (GitLab) as the factory needs it. */
public record PullRequest(long number, String url, String branch, boolean draft) {}
