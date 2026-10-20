package com.anode.factory.prwatch;

import com.anode.gitlab.GitLabWebhookEvent;
import java.util.List;
import org.gitlab4j.api.webhook.Event;
import org.gitlab4j.api.webhook.EventUser;
import org.gitlab4j.api.webhook.MergeRequestEvent;
import org.gitlab4j.api.webhook.NoteEvent;
import org.gitlab4j.api.webhook.PipelineEvent;
import org.gitlab4j.api.webhook.PushEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** GitLab webhook deliveries (secret checked by the starter) as PR events. */
@Component
class GitLabEvents {

    private final PrWatch watch;

    GitLabEvents(PrWatch watch) {
        this.watch = watch;
    }

    @EventListener
    void on(GitLabWebhookEvent delivery) {
        if (delivery.event() instanceof PushEvent push && push.getRef() != null && push.getRef().startsWith("refs/heads/")) {
            watch.pushed("gitlab:" + delivery.uuid(), "gitlab:" + push.getProject().getPathWithNamespace(),
                    push.getRef().substring("refs/heads/".length()));
            return;
        }
        normalize(delivery.uuid(), delivery.event()).forEach(watch::receive);
    }

    static List<PrEvent> normalize(String uuid, Event event) {
        String id = "gitlab:" + uuid;
        return switch (event) {
            case MergeRequestEvent mr -> {
                String kind = switch (String.valueOf(mr.getObjectAttributes().getAction())) {
                    case "merge" -> "merged";
                    case "close" -> "closed";
                    case "approved" -> "approved";
                    default -> null;
                };
                if (kind == null) {
                    yield List.of();
                }
                String repository = "gitlab:" + mr.getProject().getPathWithNamespace();
                long number = mr.getObjectAttributes().getIid();
                // approvals come from a person; merge and close from the forge
                yield List.of(kind.equals("approved")
                        ? authored(id, repository, number, kind, mr.getUser(), null, mr.getObjectAttributes().getUrl())
                        : new PrEvent(id, repository, number, kind, null, false, null, null));
            }
            case NoteEvent note when note.getObjectAttributes().getNoteableType() == NoteEvent.NoteableType.MERGE_REQUEST
                    && note.getMergeRequest() != null -> {
                PrEvent comment = authored(id, "gitlab:" + note.getProject().getPathWithNamespace(),
                        note.getMergeRequest().getIid(), "commented", note.getUser(), note.getObjectAttributes().getNote(),
                        note.getObjectAttributes().getUrl());
                yield List.of(new PrEvent(comment.id(), comment.repository(), comment.number(), comment.kind(),
                        comment.author(), comment.bot(), comment.body(), comment.url(),
                        note.getObjectAttributes().getDiscussionId()));
            }
            case PipelineEvent pipeline when pipeline.getMergeRequest() != null -> {
                String kind = switch (String.valueOf(pipeline.getObjectAttributes().getStatus())) {
                    case "success" -> "checks_passed";
                    case "failed", "canceled" -> "checks_failed";
                    default -> null; // running, pending...
                };
                yield kind == null ? List.of() : List.of(new PrEvent(id,
                        "gitlab:" + pipeline.getProject().getPathWithNamespace(), pipeline.getMergeRequest().getIid(),
                        kind, null, false, null, pipeline.getObjectAttributes().getUrl()));
            }
            default -> List.of();
        };
    }

    // ponytail: bots told by their username (GitLab bot users are named *_bot*); check the user's bot flag if fooled
    private static PrEvent authored(String id, String repository, long number, String kind, EventUser user, String body,
                                    String url) {
        String username = user.getUsername();
        return new PrEvent(id, repository, number, kind, username, username.contains("_bot"), body, url);
    }
}
