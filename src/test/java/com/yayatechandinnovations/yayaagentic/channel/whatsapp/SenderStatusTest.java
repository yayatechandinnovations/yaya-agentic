package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * State-machine smoke. Encodes the §6 transition diagram so a future edit
 * to {@code SenderStatus.legalTransitions} that drifts from the design has
 * to break this test before it breaks production.
 */
class SenderStatusTest {

    @Test
    void draft_can_submit_or_revoke() {
        assertThat(SenderStatus.DRAFT.canTransitionTo(SenderStatus.SUBMITTING)).isTrue();
        assertThat(SenderStatus.DRAFT.canTransitionTo(SenderStatus.REVOKED)).isTrue();
        assertThat(SenderStatus.DRAFT.canTransitionTo(SenderStatus.ACTIVE)).isFalse();
    }

    @Test
    void submitting_resolves_to_pending_or_failed() {
        assertThat(SenderStatus.SUBMITTING.canTransitionTo(SenderStatus.PENDING_REVIEW)).isTrue();
        assertThat(SenderStatus.SUBMITTING.canTransitionTo(SenderStatus.FAILED)).isTrue();
        assertThat(SenderStatus.SUBMITTING.canTransitionTo(SenderStatus.ACTIVE)).isFalse();
    }

    @Test
    void pending_review_resolves_to_active_or_failed() {
        assertThat(SenderStatus.PENDING_REVIEW.canTransitionTo(SenderStatus.ACTIVE)).isTrue();
        assertThat(SenderStatus.PENDING_REVIEW.canTransitionTo(SenderStatus.FAILED)).isTrue();
        assertThat(SenderStatus.PENDING_REVIEW.canTransitionTo(SenderStatus.SUSPENDED)).isFalse();
    }

    @Test
    void active_can_suspend_or_revoke_but_not_resubmit() {
        assertThat(SenderStatus.ACTIVE.canTransitionTo(SenderStatus.SUSPENDED)).isTrue();
        assertThat(SenderStatus.ACTIVE.canTransitionTo(SenderStatus.REVOKED)).isTrue();
        assertThat(SenderStatus.ACTIVE.canTransitionTo(SenderStatus.SUBMITTING)).isFalse();
    }

    @Test
    void suspended_resumes_to_active() {
        assertThat(SenderStatus.SUSPENDED.canTransitionTo(SenderStatus.ACTIVE)).isTrue();
        assertThat(SenderStatus.SUSPENDED.canTransitionTo(SenderStatus.REVOKED)).isTrue();
    }

    @Test
    void failed_can_be_edited_back_to_draft() {
        assertThat(SenderStatus.FAILED.canTransitionTo(SenderStatus.DRAFT)).isTrue();
        assertThat(SenderStatus.FAILED.canTransitionTo(SenderStatus.REVOKED)).isTrue();
        assertThat(SenderStatus.FAILED.canTransitionTo(SenderStatus.SUBMITTING)).isFalse();
    }

    @Test
    void revoked_is_terminal() {
        assertThat(SenderStatus.REVOKED.isTerminal()).isTrue();
        assertThat(SenderStatus.REVOKED.legalTransitions()).isEmpty();
    }

    @Test
    void editability_matches_draft_or_failed() {
        assertThat(SenderStatus.DRAFT.isEditable()).isTrue();
        assertThat(SenderStatus.FAILED.isEditable()).isTrue();
        for (SenderStatus other : new SenderStatus[]{
                SenderStatus.SUBMITTING, SenderStatus.PENDING_REVIEW,
                SenderStatus.ACTIVE, SenderStatus.SUSPENDED, SenderStatus.REVOKED}) {
            assertThat(other.isEditable())
                    .as("status %s should not be editable", other)
                    .isFalse();
        }
    }

    @Test
    void only_active_accepts_outbound() {
        for (SenderStatus s : SenderStatus.values()) {
            assertThat(s.acceptsOutbound())
                    .as("acceptsOutbound for %s", s)
                    .isEqualTo(s == SenderStatus.ACTIVE);
        }
    }

    @Test
    void active_pending_and_suspended_accept_inbound() {
        assertThat(SenderStatus.ACTIVE.acceptsInbound()).isTrue();
        assertThat(SenderStatus.PENDING_REVIEW.acceptsInbound()).isTrue();
        assertThat(SenderStatus.SUSPENDED.acceptsInbound()).isTrue();
        assertThat(SenderStatus.DRAFT.acceptsInbound()).isFalse();
        assertThat(SenderStatus.SUBMITTING.acceptsInbound()).isFalse();
        assertThat(SenderStatus.FAILED.acceptsInbound()).isFalse();
        assertThat(SenderStatus.REVOKED.acceptsInbound()).isFalse();
    }
}
