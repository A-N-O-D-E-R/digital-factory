package com.anode.factory.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * A workflow as written by its author (see {@code contracts/workflow-definition.schema.json}).
 * Steps are agent runs, factory actions or human gates; each step's ordered transitions say where
 * to go next.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowDefinition(String description, String start, List<Step> steps) {

    /** Terminal outcomes a transition can lead to. */
    public enum Outcome {
        DONE("@done"),
        BLOCKED("@blocked"),
        FAILED("@failed"),
        CANCELLED("@cancelled"),
        /** Its PR was merged (by a person, or by the factory under its autonomy policy). */
        MERGED("@merged"),
        /** Its PR was closed without being merged. */
        CLOSED("@closed");

        private final String target;

        Outcome(String target) {
            this.target = target;
        }

        public String target() {
            return target;
        }

        public static boolean isOutcome(String target) {
            return target != null && target.startsWith("@");
        }
    }

    public enum Kind {
        agent,
        factory,
        gate
    }

    /** One step; which optional fields apply depends on {@link #kind}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(
            String id,
            Kind kind,
            List<String> roles,
            String join,
            String action,
            Map<String, Object> with,
            String gate,
            Condition when,
            List<Transition> next,
            Map<String, Object> settings,
            Boolean enabled) {

        /** A disabled step is passed through: its transitions are taken on what is known already. */
        public boolean disabled() {
            return Boolean.FALSE.equals(enabled);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Transition(Condition when, String to, Integer maxVisits, String onExhausted) {

        /** Where the transition leads once {@link #maxVisits} is reached. */
        public String exhaustedTarget() {
            return onExhausted == null ? Outcome.BLOCKED.target() : onExhausted;
        }
    }
}
