package com.yayatechandinnovations.yayaagentic.channel.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelBindingRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ProfileRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-evaluation tests for the {@link RoutingRule} matcher. Hits the
 * service's {@code matches} method directly with synthetic principals and
 * hint maps — no Spring context, no DB.
 */
class RoutingRuleEvaluationTest {

    private final ChannelBindingService bindings = new ChannelBindingService(
            (ChannelBindingRepository) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ChannelBindingRepository.class},
                    (proxy, method, args) -> null),
            (ProfileRepository) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ProfileRepository.class},
                    (proxy, method, args) -> null),
            null,                                  // WhatsAppSenderService — never touched
            new ObjectMapper());

    @Test
    void match_any_is_unconditional() {
        assertThat(bindings.matches(new RoutingRule.MatchAny(), null, Map.of())).isTrue();
    }

    @Test
    void keyword_prefix_matches_case_insensitive_by_default() {
        RoutingRule.KeywordPrefix rule = new RoutingRule.KeywordPrefix(
                List.of("#sales", "#support"), true);
        assertThat(bindings.matches(rule, null, Map.of("channel.inbound.body", "#Sales help")))
                .isTrue();
        assertThat(bindings.matches(rule, null, Map.of("channel.inbound.body", "Hello sales")))
                .isFalse();
    }

    @Test
    void keyword_prefix_case_sensitive_when_flagged() {
        RoutingRule.KeywordPrefix rule = new RoutingRule.KeywordPrefix(
                List.of("#URGENT"), false);
        assertThat(bindings.matches(rule, null, Map.of("channel.inbound.body", "#urgent please")))
                .isFalse();
        assertThat(bindings.matches(rule, null, Map.of("channel.inbound.body", "#URGENT help")))
                .isTrue();
    }

    @Test
    void locale_rule_matches_exact_and_language_prefix() {
        RoutingRule.Locale rule = new RoutingRule.Locale(Set.of("es"));
        assertThat(bindings.matches(rule, null, Map.of("channel.locale", "es-DO"))).isTrue();
        assertThat(bindings.matches(rule, null, Map.of("channel.locale", "es"))).isTrue();
        assertThat(bindings.matches(rule, null, Map.of("channel.locale", "en-US"))).isFalse();
    }

    @Test
    void end_user_claim_rule_requires_principal() {
        RoutingRule.EndUserClaim rule = new RoutingRule.EndUserClaim("tier", "vip");
        assertThat(bindings.matches(rule, null, Map.of())).isFalse();

        Principal vip = new Principal(
                "whatsapp:+1", new Ids.TenantId("t"),
                Set.of("channel:whatsapp"),
                Map.of("tier", "vip"),
                Instant.now());
        assertThat(bindings.matches(rule, vip, Map.of())).isTrue();

        Principal plain = new Principal(
                "whatsapp:+2", new Ids.TenantId("t"),
                Set.of("channel:whatsapp"),
                Map.of("tier", "free"),
                Instant.now());
        assertThat(bindings.matches(rule, plain, Map.of())).isFalse();
    }

    @Test
    void keyword_rule_with_empty_list_never_matches() {
        RoutingRule.KeywordPrefix empty = new RoutingRule.KeywordPrefix(List.of(), true);
        assertThat(bindings.matches(empty, null, Map.of("channel.inbound.body", "anything")))
                .isFalse();
    }
}
