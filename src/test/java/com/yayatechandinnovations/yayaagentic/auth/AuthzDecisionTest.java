package com.yayatechandinnovations.yayaagentic.auth;

import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpHint;
import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpMethod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthzDecisionTest {

    @Test
    void allow_is_not_a_denial() {
        AuthzDecision allow = AuthzDecision.Allow.none();
        assertThat(allow.isDenied()).isFalse();
    }

    @Test
    void plain_deny_is_a_denial() {
        AuthzDecision deny = new AuthzDecision.Deny("safe", "audit");
        assertThat(deny.isDenied()).isTrue();
        assertThat(deny.userSafeReason()).isEqualTo("safe");
        assertThat(deny.auditReason()).isEqualTo("audit");
    }

    @Test
    void deny_offer_step_up_is_also_a_denial() {
        AuthzDecision dsu = new AuthzDecision.DenyOfferStepUp(
                "Please verify it's you",
                "scope-insufficient: tool:transfer_funds",
                new StepUpHint(new StepUpMethod.OtpEmail(),
                        "tool:transfer_funds",
                        Duration.ofMinutes(15),
                        Map.of()));
        assertThat(dsu.isDenied()).isTrue();
        assertThat(dsu.userSafeReason()).isEqualTo("Please verify it's you");
        assertThat(dsu.auditReason())
                .isEqualTo("scope-insufficient: tool:transfer_funds");
    }

    @Test
    void allow_throws_when_asked_for_a_reason() {
        AuthzDecision allow = AuthzDecision.Allow.none();
        assertThatThrownBy(allow::userSafeReason)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(allow::auditReason)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deny_offer_step_up_requires_a_hint() {
        assertThatThrownBy(() -> new AuthzDecision.DenyOfferStepUp(
                "safe", "audit", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("use Deny for plain denials");
    }

    @Test
    void step_up_hint_rejects_blank_audience_and_non_positive_ttl() {
        var method = new StepUpMethod.OtpEmail();
        assertThatThrownBy(() -> new StepUpHint(method, "  ",
                Duration.ofMinutes(15), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StepUpHint(method, "tool:x",
                Duration.ZERO, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void custom_step_up_method_requires_provider_id() {
        assertThatThrownBy(() -> new StepUpMethod.Custom(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
