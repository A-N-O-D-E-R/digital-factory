package com.anode.factory.prwatch;

import com.anode.factory.forge.FailedCheck;
import com.anode.factory.forge.Forges;
import com.anode.factory.forge.PullRequest;
import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.Repositories;
import com.anode.factory.workorder.FactoryAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Waits for the next trusted event on the work order's PR and states it as facts. */
@Component
class WatchPrAction implements FactoryAction {

    private static final Logger log = LoggerFactory.getLogger(WatchPrAction.class);

    private final PrWatch watch;
    private final Repositories repositories;
    private final Forges forges;

    WatchPrAction(PrWatch watch, Repositories repositories, Forges forges) {
        this.watch = watch;
        this.repositories = repositories;
        this.forges = forges;
    }

    @Override
    public String name() {
        return "watch-pr";
    }

    /** The failed checks with the end of their logs, or why they could not be had (the fix goes on). */
    private Object failures(Context context) {
        try {
            CodeRepository repository = repositories.get(context.repository());
            long number = ((Number) context.facts().get("pr.number")).longValue();
            return forges.of(repository).failedChecks(repository, new PullRequest(number, null, null, false));
        } catch (RuntimeException e) {
            log.warn("Cannot read the failed checks of work order {}", context.workOrderId(), e);
            return List.of(new FailedCheck("unknown", null, "The factory could not read the failed checks: "
                    + e.getMessage(), null));
        }
    }

    @Override
    public Result execute(Context context) {
        return watch.next(context.workOrderId()).map(event -> {
            Map<String, Object> facts = new HashMap<>();
            facts.put("pr.event", event.kind());
            switch (event.kind()) {
                case "checks_passed" -> facts.put("pr.checks.passed", true);
                case "checks_failed" -> {
                    facts.put("pr.checks.passed", false);
                    // what the fix iteration starts from; untrusted input like any CI output
                    facts.put("pr.checks.failures", failures(context));
                }
                case "changes_requested", "commented" -> {
                    // what feedback-triage works on; untrusted input whoever wrote it
                    Map<String, Object> feedback = new HashMap<>();
                    feedback.put("id", event.id());
                    feedback.put("kind", event.kind());
                    feedback.put("author", event.author());
                    feedback.put("body", event.body());
                    feedback.put("url", event.url());
                    facts.put("pr.feedback", feedback);
                }
                case "merged" -> {
                    if (event.commit() != null) {
                        facts.put("pr.mergeCommit", event.commit()); // to tell a later revert
                    }
                }
                default -> {
                    // closed, approved: the event is the fact
                }
            }
            return Result.proceed(facts);
        }).orElseGet(Result::waitForEvent);
    }
}
