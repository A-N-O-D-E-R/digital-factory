package com.anode.factory.workflow;

import java.util.Map;
import java.util.Objects;

/** Evaluates a {@link Condition} against the facts known about a work order. */
public final class Conditions {

    private Conditions() {}

    /** A fact that is not known yet makes its comparison false (and its negation true). */
    public static boolean holds(Condition condition, Map<String, Object> facts) {
        return switch (condition) {
            case null -> true;
            case Condition.AllOf all -> all.allOf().stream().allMatch(c -> holds(c, facts));
            case Condition.AnyOf any -> any.anyOf().stream().anyMatch(c -> holds(c, facts));
            case Condition.Not not -> !holds(not.not(), facts);
            case Condition.Fact fact -> compare(fact, facts.get(fact.fact()));
        };
    }

    private static boolean compare(Condition.Fact fact, Object value) {
        if (value == null) {
            return false;
        }
        if (fact.equalTo() != null) {
            return same(value, fact.equalTo());
        }
        if (fact.in() != null) {
            return fact.in().stream().anyMatch(candidate -> same(value, candidate));
        }
        if (fact.greaterThan() != null) {
            return value instanceof Number n && n.doubleValue() > fact.greaterThan();
        }
        if (fact.lessThan() != null) {
            return value instanceof Number n && n.doubleValue() < fact.lessThan();
        }
        return false;
    }

    /** Numbers compare by value whatever their Java type; everything else by equality. */
    private static boolean same(Object value, Object expected) {
        if (value instanceof Number a && expected instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        return Objects.equals(value, expected) || Objects.equals(String.valueOf(value), String.valueOf(expected))
                && !(value instanceof Boolean) && !(expected instanceof Boolean);
    }
}
