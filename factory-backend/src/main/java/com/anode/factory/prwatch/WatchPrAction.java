package com.anode.factory.prwatch;

import com.anode.factory.workorder.FactoryAction;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Waits for the next trusted event on the work order's PR and states it as facts. */
@Component
class WatchPrAction implements FactoryAction {

    private final PrWatch watch;

    WatchPrAction(PrWatch watch) {
        this.watch = watch;
    }

    @Override
    public String name() {
        return "watch-pr";
    }

    @Override
    public Result execute(Context context) {
        return watch.next(context.workOrderId()).map(event -> {
            Map<String, Object> facts = new HashMap<>();
            facts.put("pr.event", event.kind());
            switch (event.kind()) {
                case "checks_passed" -> facts.put("pr.checks.passed", true);
                case "checks_failed" -> facts.put("pr.checks.passed", false);
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
                default -> {
                    // merged, closed, approved: the event is the fact
                }
            }
            return Result.proceed(facts);
        }).orElseGet(Result::waitForEvent);
    }
}
