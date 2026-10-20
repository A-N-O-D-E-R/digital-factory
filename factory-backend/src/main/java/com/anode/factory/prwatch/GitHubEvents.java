package com.anode.factory.prwatch;

import com.anode.github.GitHubWebhookEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
            List<String> messages = new ArrayList<>();
            payload.path("commits").forEach(commit -> messages.add(commit.path("message").asString("")));
            watch.pushed("github:" + delivery.deliveryId(), "github:" + payload.path("repository").path("full_name").asString(),
                    payload.path("ref").asString().substring("refs/heads/".length()), messages);
            return;
        }
        normalize(delivery.event(), delivery.deliveryId(), payload).forEach(watch::receive);
    }

    /**
     * Event ids are the forge's own (comment, review, the head commit's checks), as polling gives
     * them (GitHubForge.activity): an event seen both ways counts once. The delivery id stands in
     * when a payload has none.
     */
    static List<PrEvent> normalize(String event, String deliveryId, JsonNode payload) {
        String delivery = "github:" + deliveryId;
        String path = payload.path("repository").path("full_name").asString();
        String repository = "github:" + path;
        String action = payload.path("action").asString();
        return switch (event) {
            case "pull_request" -> {
                if (!action.equals("closed")) {
                    yield List.of();
                }
                long number = payload.path("pull_request").path("number").asLong();
                String kind = payload.path("pull_request").path("merged").asBoolean() ? "merged" : "closed";
                yield List.of(system("github:" + path + "#" + number + ":" + kind, repository, number, kind)
                        .withCommit(payload.path("pull_request").path("merge_commit_sha").asString(null)));
            }
            case "pull_request_review" -> action.equals("submitted")
                    ? kind(payload.path("review").path("state").asString())
                            .map(kind -> List.of(authored(id("github:review:", payload.path("review"), delivery),
                                    repository, payload.path("pull_request").path("number").asLong(), kind,
                                    payload.path("review"))))
                            .orElse(List.of())
                    : List.of();
            case "pull_request_review_comment" -> action.equals("created")
                    ? List.of(thread(authored(id("github:review-comment:", payload.path("comment"), delivery), repository,
                            payload.path("pull_request").path("number").asLong(), "commented", payload.path("comment")),
                            payload.path("comment")))
                    : List.of();
            case "issue_comment" -> action.equals("created") && payload.path("issue").has("pull_request")
                    ? List.of(authored(id("github:comment:", payload.path("comment"), delivery), repository,
                            payload.path("issue").path("number").asLong(), "commented", payload.path("comment")))
                    : List.of();
            case "check_suite" -> action.equals("completed") ? checks(delivery, repository, payload) : List.of();
            default -> List.of();
        };
    }

    private static String id(String prefix, JsonNode item, String delivery) {
        return item.hasNonNull("id") ? prefix + item.get("id").asString() : delivery;
    }

    private static java.util.Optional<String> kind(String reviewState) {
        return switch (reviewState.toLowerCase(Locale.ROOT)) {
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
        String head = suite.path("head_sha").asString(null);
        for (JsonNode pr : suite.path("pull_requests")) {
            long number = pr.path("number").asLong();
            // one event per outcome of the head commit's checks, however many suites
            events.add(system((head == null ? id : "github:checks:" + head + ":" + kind) + "#" + number, repository,
                    number, kind));
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
