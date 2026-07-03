package com.yayatechandinnovations.yayaagentic.startflow;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Where a bound value comes from when a {@link StartStep.RunTool} assembles
 * its arguments. A sealed union so the whole set of sources is enumerable and
 * lintable — never free-form code. Persisted as JSONB with a {@code kind}
 * discriminator (mirrors {@code channel.chat.AuthConfig}). See
 * {@code docs/design/session-start-flows.md} §5.2.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ValueSource.Literal.class, name = "Literal"),
        @JsonSubTypes.Type(value = ValueSource.Channel.class, name = "Channel"),
        @JsonSubTypes.Type(value = ValueSource.Principal.class, name = "Principal"),
        @JsonSubTypes.Type(value = ValueSource.Entity.class, name = "Entity"),
        @JsonSubTypes.Type(value = ValueSource.StepOutput.class, name = "StepOutput"),
})
public sealed interface ValueSource
        permits ValueSource.Literal, ValueSource.Channel, ValueSource.Principal,
                ValueSource.Entity, ValueSource.StepOutput {

    /** A constant configured by the operator. */
    record Literal(Object value) implements ValueSource {}

    /** A channel fact the framework already captured at start (endUserPhone, endUserLocale, …). */
    record Channel(String field) implements ValueSource {}

    /** A verified claim off the principal — {@code "subject"}/{@code "sub"}, {@code "tenant"}, or a dotted claim path. */
    record Principal(String claimPath) implements ValueSource {}

    /** A named value on the shared blackboard ({@code EntityMemory}). */
    record Entity(String entityName) implements ValueSource {}

    /** RFC-6901 pointer into a prior step's raw result value. */
    record StepOutput(String stepKey, String jsonPointer) implements ValueSource {}
}
