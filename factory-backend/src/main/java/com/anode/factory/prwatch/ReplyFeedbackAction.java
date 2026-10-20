package com.anode.factory.prwatch;

import com.anode.factory.forge.Forge;
import com.anode.factory.forge.Forges;
import com.anode.factory.forge.PullRequest;
import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.Repositories;
import com.anode.factory.run.Run;
import com.anode.factory.run.RunQueue;
import com.anode.factory.run.RunStatus;
import com.anode.factory.workorder.FactoryAction;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers the triaged feedback in its threads once the fix is pushed: a change points to the pushed
 * commit, the other decisions post the triage's answer; addressed threads are resolved where the forge
 * allows. Each piece of feedback is answered once.
 */
@Component
class ReplyFeedbackAction implements FactoryAction {

    private final ForgeEventRepository events;
    private final RunQueue runs;
    private final Repositories repositories;
    private final Forges forges;
    private final JsonMapper json;
    private final Clock clock;

    ReplyFeedbackAction(ForgeEventRepository events, RunQueue runs, Repositories repositories, Forges forges,
                        JsonMapper json, Clock clock) {
        this.events = events;
        this.runs = runs;
        this.repositories = repositories;
        this.forges = forges;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "reply-feedback";
    }

    @Override
    @Transactional
    public Result execute(Context context) {
        List<Run> all = runs.runsOf(context.workOrderId());
        Optional<JsonNode> triage = latest(all, "feedback-triage");
        if (triage.isEmpty() || !(context.facts().get("pr.number") instanceof Number number)) {
            return Result.proceed(Map.of());
        }
        String commit = latest(all, "push-branch").map(push -> push.get("commit").asString()).orElse(null);
        CodeRepository repository = repositories.get(context.repository());
        Forge forge = forges.of(repository);
        PullRequest pullRequest = new PullRequest(number.longValue(), null, null, false);
        for (JsonNode item : triage.get().get("items")) {
            Optional<ForgeEvent> event = events.findById(item.get("id").asString())
                    .filter(e -> e.workOrderId().equals(context.workOrderId()) && e.repliedAt() == null);
            if (event.isEmpty()) {
                continue; // a failed check, or answered already
            }
            String decision = item.get("decision").asString();
            String reply = decision.equals("CHANGE")
                    ? "Done" + (commit == null ? "." : " in " + commit + ".")
                    : item.path("answer").asString("");
            if (!reply.isBlank()) {
                forge.reply(repository, pullRequest, event.get().thread(), reply);
            }
            if (decision.equals("CHANGE") || decision.equals("ALREADY_DONE")) {
                forge.resolve(repository, pullRequest, event.get().thread());
            }
            event.get().replied(clock.instant());
        }
        return Result.proceed(Map.of());
    }

    private Optional<JsonNode> latest(List<Run> all, String task) {
        return all.stream()
                .filter(run -> run.task().equals(task) && run.status() == RunStatus.SUCCEEDED)
                .reduce((first, second) -> second)
                .map(run -> json.readTree(run.output()));
    }
}
