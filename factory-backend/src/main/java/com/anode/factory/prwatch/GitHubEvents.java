package com.anode.factory.prwatch;

import com.anode.github.GitHubWebhookEvent;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GitHub webhook deliveries (signature checked by the starter) as PR events. */
@Component
class GitHubEvents {

    private final PrWatch watch;
    private final JsonMapper json;

    GitHubEvents(PrWatch watch, JsonMapper json) {
        this.watch = watch;
        this.json = json;
    }

    /** In the webhook request: a failure makes GitHub deliver again. */
    @EventListener
    void on(GitHubWebhookEvent delivery) {
        JsonNode payload = json.readTree(delivery.payload());
        if (delivery.event().equals("push") && payload.path("ref").asString().startsWith("refs/heads/")) {
            watch.pushed("github:" + delivery.deliveryId(), "github:" + payload.path("repository").path("full_name").asString(),
                    payload.path("ref").asString().substring("refs/heads/".length()));
            return;
        }
        normalize(delivery.event(), delivery.deliveryId(), payload).forEach(watch::receive);
    }

    static List<PrEvent> normalize(String event, String deliveryId, JsonNode payload) {
        String id = "github:" + deliveryId;
        String repository = "github:" + payload.path("repository").path("full_name").asString();
        String action = payload.path("action").asString();
        return switch (event) {
            case "pull_request" -> action.equals("closed")
                    ? List.of(system(id, repository, payload.path("pull_request").path("number").asLong(),
                            payload.path("pull_request").path("merged").asBoolean() ? "merged" : "closed"))
                    : List.of();
            case "pull_request_review" -> action.equals("submitted")
                    ? kind(payload.path("review").path("state").asString())
                            .map(kind -> List.of(authored(id, repository, payload.path("pull_request").path("number").asLong(),
                                    kind, payload.path("review"))))
                            .orElse(List.of())
                    : List.of();
            case "pull_request_review_comment" -> action.equals("created")
                    ? List.of(thread(authored(id, repository, payload.path("pull_request").path("number").asLong(),
                            "commented", payload.path("comment")), payload.path("comment")))
                    : List.of();
            case "issue_comment" -> action.equals("created") && payload.path("issue").has("pull_request")
                    ? List.of(authored(id, repository, payload.path("issue").path("number").asLong(), "commented",
                            payload.path("comment")))
                    : List.of();
            case "check_suite" -> action.equals("completed") ? checks(id, repository, payload) : List.of();
            default -> List.of();
        };
    }

    private static java.util.Optional<String> kind(String reviewState) {
        return switch (reviewState.toLowerCase()) {
            case "approved" -> java.util.Optional.of("approved");
            case "changes_requested" -> java.util.Optional.of("changes_requested");
            case "commented" -> java.util.Optional.of("commented");
            default -> java.util.Optional.empty();
        };
    }

    private static List<PrEvent> checks(String id, String repository, JsonNode payload) {
        JsonNode suite = payload.path("check_suite");
        String kind = switch (suite.path("conclusion").asString()) {
            case "success", "neutral", "skipped" -> "checks_passed";
            default -> "checks_failed";
        };
        List<PrEvent> events = new ArrayList<>();
        for (JsonNode pr : suite.path("pull_requests")) {
            long number = pr.path("number").asLong();
            events.add(system(id + "#" + number, repository, number, kind));
        }
        return events;
    }

    /** A review comment: replies go to its thread (the comment replied to). */
    private static PrEvent thread(PrEvent event, JsonNode comment) {
        String thread = comment.path("in_reply_to_id").asString(comment.path("id").asString(null));
        return new PrEvent(event.id(), event.repository(), event.number(), event.kind(), event.author(), event.bot(),
                event.body(), event.url(), thread);
    }

    private static PrEvent system(String id, String repository, long number, String kind) {
        return new PrEvent(id, repository, number, kind, null, false, null, null);
    }

    /** A review or comment: its author decides whether it is trusted. */
    private static PrEvent authored(String id, String repository, long number, String kind, JsonNode item) {
        JsonNode user = item.path("user");
        String login = user.path("login").asString();
        boolean bot = "Bot".equals(user.path("type").asString()) || login.endsWith("[bot]");
        return new PrEvent(id, repository, number, kind, login, bot, item.path("body").asString(null),
                item.path("html_url").asString(null));
    }
}
