package com.anode.factory.workorder;

import java.util.Map;
import java.util.UUID;

/**
 * A built-in factory step action (push-branch, open-draft-pr, merge...). Implementations live in
 * the modules owning the integrations; they are called with the work order locked.
 */
public interface FactoryAction {

    /** Action name used in workflow definitions. */
    String name();

    Result execute(Context context);

    /** What the action knows about the work order it acts on. */
    record Context(UUID workOrderId, String repository, String branch, String title, String stepId, int visit,
                   Map<String, Object> facts, Map<String, Object> with) {}

    /** Proceed (with new facts), wait to be resumed, or fail the work order. */
    sealed interface Result {
        record Proceed(Map<String, Object> facts) implements Result {}

        record Wait() implements Result {}

        record Fail(String reason) implements Result {}

        static Result proceed() {
            return new Proceed(Map.of());
        }

        static Result proceed(Map<String, Object> facts) {
            return new Proceed(facts);
        }

        static Result waitForEvent() {
            return new Wait();
        }

        static Result fail(String reason) {
            return new Fail(reason);
        }
    }
}
