package com.anode.factory.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * A predicate over facts, from a closed grammar (see workflow-definition.schema.json): never code.
 * The JSON form is told apart by its keys.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({
    @JsonSubTypes.Type(Condition.Fact.class),
    @JsonSubTypes.Type(Condition.AllOf.class),
    @JsonSubTypes.Type(Condition.AnyOf.class),
    @JsonSubTypes.Type(Condition.Not.class)
})
public sealed interface Condition {

    /** One fact compared with one operator (exactly one of the four is set). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Fact(
            String fact,
            @JsonProperty("equals") Object equalTo,
            List<Object> in,
            Double greaterThan,
            Double lessThan)
            implements Condition {}

    record AllOf(List<Condition> allOf) implements Condition {}

    record AnyOf(List<Condition> anyOf) implements Condition {}

    record Not(Condition not) implements Condition {}
}
