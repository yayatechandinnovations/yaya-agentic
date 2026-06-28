package com.yayatechandinnovations.yayaagentic.channel.sms;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * State-machine smoke for the A2P 10DLC compliance aggregates. Encodes the
 * {@code sms-channel-design.md} §6.2 transition diagrams so a future edit that
 * drifts from the design breaks here before it breaks production — same posture
 * as {@code channel.whatsapp.SenderStatusTest}.
 */
class A2pComplianceStateMachineTest {

    // ---- BrandStatus --------------------------------------------------------

    @Test
    void brand_draft_only_submits() {
        assertThat(BrandStatus.DRAFT.canTransitionTo(BrandStatus.SUBMITTING)).isTrue();
        assertThat(BrandStatus.DRAFT.canTransitionTo(BrandStatus.VERIFIED)).isFalse();
    }

    @Test
    void brand_submitting_resolves_to_pending_or_failed() {
        assertThat(BrandStatus.SUBMITTING.canTransitionTo(BrandStatus.PENDING_VERIFICATION)).isTrue();
        assertThat(BrandStatus.SUBMITTING.canTransitionTo(BrandStatus.FAILED)).isTrue();
        assertThat(BrandStatus.SUBMITTING.canTransitionTo(BrandStatus.VERIFIED)).isFalse();
    }

    @Test
    void brand_pending_resolves_to_verified_or_failed() {
        assertThat(BrandStatus.PENDING_VERIFICATION.canTransitionTo(BrandStatus.VERIFIED)).isTrue();
        assertThat(BrandStatus.PENDING_VERIFICATION.canTransitionTo(BrandStatus.FAILED)).isTrue();
    }

    @Test
    void brand_failed_edits_back_to_draft() {
        assertThat(BrandStatus.FAILED.canTransitionTo(BrandStatus.DRAFT)).isTrue();
        assertThat(BrandStatus.FAILED.isEditable()).isTrue();
        assertThat(BrandStatus.DRAFT.isEditable()).isTrue();
    }

    @Test
    void only_verified_brand_can_host_campaign_submission() {
        for (BrandStatus s : BrandStatus.values()) {
            assertThat(s.canHostCampaignSubmission())
                    .as("canHostCampaignSubmission for %s", s)
                    .isEqualTo(s == BrandStatus.VERIFIED);
        }
    }

    // ---- CampaignStatus -----------------------------------------------------

    @Test
    void campaign_happy_path_to_approved() {
        assertThat(CampaignStatus.DRAFT.canTransitionTo(CampaignStatus.SUBMITTING)).isTrue();
        assertThat(CampaignStatus.SUBMITTING.canTransitionTo(CampaignStatus.PENDING_REVIEW)).isTrue();
        assertThat(CampaignStatus.PENDING_REVIEW.canTransitionTo(CampaignStatus.APPROVED)).isTrue();
    }

    @Test
    void approved_campaign_can_be_carrier_suspended_then_resumed() {
        assertThat(CampaignStatus.APPROVED.canTransitionTo(CampaignStatus.SUSPENDED)).isTrue();
        assertThat(CampaignStatus.SUSPENDED.canTransitionTo(CampaignStatus.APPROVED)).isTrue();
        assertThat(CampaignStatus.SUSPENDED.canTransitionTo(CampaignStatus.FAILED)).isTrue();
    }

    @Test
    void only_approved_campaign_permits_sending() {
        for (CampaignStatus s : CampaignStatus.values()) {
            assertThat(s.permitsSending())
                    .as("permitsSending for %s", s)
                    .isEqualTo(s == CampaignStatus.APPROVED);
        }
    }

    @Test
    void campaign_cannot_skip_review() {
        assertThat(CampaignStatus.DRAFT.canTransitionTo(CampaignStatus.APPROVED)).isFalse();
        assertThat(CampaignStatus.SUBMITTING.canTransitionTo(CampaignStatus.APPROVED)).isFalse();
    }

    // ---- A2pUseCase ---------------------------------------------------------

    @Test
    void gambling_and_sweepstakes_are_restricted() {
        assertThat(A2pUseCase.GAMBLING.isRestricted()).isTrue();
        assertThat(A2pUseCase.SWEEPSTAKES.isRestricted()).isTrue();
        assertThat(A2pUseCase.HIGH_RISK_FINANCIAL.isRestricted()).isTrue();
    }

    @Test
    void common_use_cases_are_not_restricted() {
        assertThat(A2pUseCase.ACCOUNT_NOTIFICATION.isRestricted()).isFalse();
        assertThat(A2pUseCase.TWO_FACTOR_AUTH.isRestricted()).isFalse();
        assertThat(A2pUseCase.MARKETING.isRestricted()).isFalse();
        assertThat(A2pUseCase.CUSTOMER_CARE.isRestricted()).isFalse();
    }
}
