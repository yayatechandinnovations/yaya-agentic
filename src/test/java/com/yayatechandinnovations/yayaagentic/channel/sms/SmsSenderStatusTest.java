package com.yayatechandinnovations.yayaagentic.channel.sms;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the SMS sender's 7-state machine to the design (§5.2). Intentionally
 * parallels {@code channel.whatsapp.SenderStatusTest} — if the two drift, that
 * is a signal the shared-status unification seam should be taken.
 */
class SmsSenderStatusTest {

    @Test
    void draft_submits_or_revokes() {
        assertThat(SmsSenderStatus.DRAFT.canTransitionTo(SmsSenderStatus.SUBMITTING)).isTrue();
        assertThat(SmsSenderStatus.DRAFT.canTransitionTo(SmsSenderStatus.REVOKED)).isTrue();
        assertThat(SmsSenderStatus.DRAFT.canTransitionTo(SmsSenderStatus.ACTIVE)).isFalse();
    }

    @Test
    void pending_review_resolves_to_active_or_failed() {
        assertThat(SmsSenderStatus.PENDING_REVIEW.canTransitionTo(SmsSenderStatus.ACTIVE)).isTrue();
        assertThat(SmsSenderStatus.PENDING_REVIEW.canTransitionTo(SmsSenderStatus.FAILED)).isTrue();
    }

    @Test
    void only_active_accepts_outbound() {
        for (SmsSenderStatus s : SmsSenderStatus.values()) {
            assertThat(s.acceptsOutbound()).as("acceptsOutbound for %s", s)
                    .isEqualTo(s == SmsSenderStatus.ACTIVE);
        }
    }

    @Test
    void inbound_accepted_in_active_pending_suspended() {
        assertThat(SmsSenderStatus.ACTIVE.acceptsInbound()).isTrue();
        assertThat(SmsSenderStatus.PENDING_REVIEW.acceptsInbound()).isTrue();
        assertThat(SmsSenderStatus.SUSPENDED.acceptsInbound()).isTrue();
        assertThat(SmsSenderStatus.DRAFT.acceptsInbound()).isFalse();
    }

    @Test
    void revoked_is_terminal() {
        assertThat(SmsSenderStatus.REVOKED.isTerminal()).isTrue();
        assertThat(SmsSenderStatus.REVOKED.legalTransitions()).isEmpty();
    }

    @Test
    void us_market_needs_a2p_campaign_and_dr_needs_a_bundle() {
        assertThat(SmsMarket.US.requiresA2pCampaign()).isTrue();
        assertThat(SmsMarket.US.requiresRegulatoryBundle()).isFalse();
        assertThat(SmsMarket.DO.requiresA2pCampaign()).isFalse();
        assertThat(SmsMarket.DO.requiresRegulatoryBundle()).isTrue();
    }
}
