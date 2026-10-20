package com.anode.factory.forge;

import java.util.Map;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** What GitHub only does through GraphQL: taking a PR out of draft, resolving a review thread. */
final class GitHubGraphql {

    private final RestClient client;

    /** {@code apiUrl}: the REST API's (https://api.github.com, or GitHub Enterprise's …/api/v3). */
    GitHubGraphql(String apiUrl, String token) {
        String url = apiUrl.endsWith("/api/v3") ? apiUrl.replace("/api/v3", "/api/graphql") : apiUrl + "/graphql";
        this.client = RestClient.builder().baseUrl(url).defaultHeader("Authorization", "Bearer " + token).build();
    }

    JsonNode call(String query, Map<String, Object> variables) {
        JsonNode response = client.post().body(Map.of("query", query, "variables", variables)).retrieve().body(JsonNode.class);
        if (response == null || response.path("errors").size() > 0) {
            throw new IllegalStateException("GitHub GraphQL: " + (response == null ? "no answer" : response.get("errors")));
        }
        return response.get("data");
    }

    void markReady(String pullRequestNodeId) {
        call("mutation($id: ID!) { markPullRequestReadyForReview(input: {pullRequestId: $id}) { clientMutationId } }",
                Map.of("id", pullRequestNodeId));
    }

    /** Resolves the review thread started by comment {@code commentId}; false when no thread starts with it. */
    // ponytail: the PR's first 100 threads; page through them if PRs get that discussed
    boolean resolveThread(String owner, String name, long number, String commentId) {
        JsonNode threads = call("""
                query($owner: String!, $name: String!, $number: Int!) {
                  repository(owner: $owner, name: $name) { pullRequest(number: $number) {
                    reviewThreads(first: 100) { nodes { id isResolved comments(first: 1) { nodes { databaseId } } } } } }
                }""", Map.of("owner", owner, "name", name, "number", number))
                .path("repository").path("pullRequest").path("reviewThreads").path("nodes");
        for (JsonNode thread : threads) {
            if (commentId.equals(thread.path("comments").path("nodes").path(0).path("databaseId").asString())) {
                if (!thread.path("isResolved").asBoolean()) {
                    call("mutation($id: ID!) { resolveReviewThread(input: {threadId: $id}) { thread { id } } }",
                            Map.of("id", thread.get("id").asString()));
                }
                return true;
            }
        }
        return false;
    }
}
