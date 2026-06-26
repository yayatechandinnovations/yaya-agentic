package com.yayatechandinnovations.yayaagentic.channel.binding;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Set;

/**
 * Inbound-message → binding match rule. A sender can carry multiple bindings
 * with different routing rules and a {@code priority}; the lowest priority
 * matching rule wins. See {@code whatsapp-channel-design.md} §8.1.
 *
 * <p>Jackson polymorphism keys on a {@code kind} discriminator so the JSONB
 * column stays trivially diff-able and inspectable from {@code psql}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RoutingRule.MatchAny.class,       name = "MatchAny"),
        @JsonSubTypes.Type(value = RoutingRule.KeywordPrefix.class,  name = "KeywordPrefix"),
        @JsonSubTypes.Type(value = RoutingRule.Locale.class,         name = "Locale"),
        @JsonSubTypes.Type(value = RoutingRule.EndUserClaim.class,   name = "EndUserClaim"),
})
public sealed interface RoutingRule
        permits RoutingRule.MatchAny,
                RoutingRule.KeywordPrefix,
                RoutingRule.Locale,
                RoutingRule.EndUserClaim {

    record MatchAny() implements RoutingRule {}

    /** First-token prefix match against the message body. */
    record KeywordPrefix(List<String> keywords, boolean caseInsensitive) implements RoutingRule {
        public KeywordPrefix {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    /** Match against the end-user locale (BCP-47 string). */
    record Locale(Set<String> locales) implements RoutingRule {
        public Locale {
            locales = locales == null ? Set.of() : Set.copyOf(locales);
        }
    }

    /** Match against a verified-principal claim. */
    record EndUserClaim(String claim, String equals) implements RoutingRule {}
}
